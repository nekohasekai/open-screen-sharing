import Foundation
import Network
import Perception
import dnssd
import os

struct NearbyComputer: Identifiable, Hashable {
    var id: String { "\(remoteProtocol.rawValue):\(name)" }
    var name: String
    var remoteProtocol = RemoteProtocol.vnc
    var hostName: String?
    var port: Int?
    var model: String?
    var addresses: Set<Data> = []

    var address: ComputerAddress? {
        guard let hostName, let port else { return nil }
        return ComputerAddress(host: hostName, port: port)
    }
}

@Perceptible
final class NearbyBrowser {
    enum State: Equatable {
        case idle
        case browsing
        case failed(String)
    }

    private(set) var computers: [NearbyComputer] = []
    private(set) var state: State = .idle
    private(set) var hasSearched = false

    @PerceptionIgnored private var browser: NWBrowser?
    @PerceptionIgnored private var windowsBrowser: NWBrowser?
    @PerceptionIgnored private var resolvers: [String: ServiceResolver] = [:]
    @PerceptionIgnored private var windowsResolvers: [String: ServiceResolver] = [:]
    @PerceptionIgnored private var windowsProbes: [String: Task<Void, Never>] = [:]
    @PerceptionIgnored private var models: [String: String] = [:]
    @PerceptionIgnored private var serviceModels: [String: String] = [:]
    @PerceptionIgnored private var activeClients = 0
    @PerceptionIgnored private var restart: Task<Void, Never>?
    @PerceptionIgnored private var search: Task<Void, Never>?
    @PerceptionIgnored private var isReady = false
    @PerceptionIgnored private var isFixed = false
    private var currentResults: Set<RemoteProtocol> = []

    /// NWBrowser can reach .ready before any results callback (observed on macOS 27.0.1 26A434).
    private static let searchPresentationDelay: TimeInterval = 2
    private static let restartDelay: TimeInterval = 1

    #if DEBUG || DEMO
    convenience init(fixed computers: [NearbyComputer]) {
        self.init()
        self.computers = computers
        state = .browsing
        hasSearched = true
        isFixed = true
        currentResults = [.vnc, .rdp]
    }
    #endif

    func start() {
        guard !isFixed else { return }
        activeClients += 1
        guard browser == nil else { return }
        browse()
        search = Task {
            try? await Task.sleep(seconds: Self.searchPresentationDelay)
            guard !Task.isCancelled else { return }
            hasSearched = true
        }
    }

    private func browse() {
        isReady = false
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_rfb._tcp", domain: "local."), using: parameters)
        browser.stateUpdateHandler = { [weak self, weak browser] newState in
            Task { @MainActor in
                guard let self, let browser, self.browser === browser else { return }
                self.handle(newState)
            }
        }
        browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
            var names: [String] = []
            var models: [String: String] = [:]
            for result in results {
                guard case let .service(name, _, _, _) = result.endpoint else { continue }
                #if os(iOS)
                if result.interfaces.contains(where: { $0.type == .loopback }) { continue }
                #endif
                names.append(name)
                if case let .bonjour(record) = result.metadata, let model = record["model"], !model.isEmpty {
                    models[name] = model
                }
            }
            Task { @MainActor in
                guard let self, let browser, self.browser === browser else { return }
                self.update(names: names, models: models)
            }
        }
        browser.start(queue: .main)
        self.browser = browser
        browseWindows(using: parameters)
        state = .browsing
    }

    // Windows advertises Delivery Optimization's peer cache on _dosvc._tcp by default, never Remote Desktop
    // (observed on Windows 11 26100 and 26200).
    private func browseWindows(using parameters: NWParameters) {
        let browser = NWBrowser(for: .bonjour(type: "_dosvc._tcp", domain: "local."), using: parameters)
        browser.stateUpdateHandler = { [weak self, weak browser] newState in
            Task { @MainActor in
                guard let self, let browser, self.windowsBrowser === browser else { return }
                self.handle(newState)
            }
        }
        browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
            let names = results.compactMap { result -> String? in
                guard case let .service(name, _, _, _) = result.endpoint,
                    !result.interfaces.contains(where: { $0.type == .loopback })
                else { return nil }
                return name
            }
            Task { @MainActor in
                guard let self, let browser, self.windowsBrowser === browser else { return }
                self.updateWindows(names: Set(names))
            }
        }
        browser.start(queue: .main)
        windowsBrowser = browser
    }

    private func updateWindows(names: Set<String>) {
        currentResults.insert(.rdp)
        computers.removeAll { $0.remoteProtocol == .rdp && !names.contains($0.name) }
        for name in windowsResolvers.keys where !names.contains(name) {
            windowsResolvers.removeValue(forKey: name)?.cancel()
            windowsProbes.removeValue(forKey: name)?.cancel()
        }
        for name in names where windowsResolvers[name] == nil {
            let resolver = ServiceResolver(name: name, type: "_dosvc._tcp") { [weak self] hostName, _ in
                self?.probeWindows(name: name, hostName: hostName)
            } addresses: { [weak self] addresses in
                guard let self, let index = computers.firstIndex(where: { $0.remoteProtocol == .rdp && $0.name == name }) else {
                    return
                }
                computers[index].addresses = addresses
            }
            windowsResolvers[name] = resolver
            resolver.start()
        }
    }

    private func probeWindows(name: String, hostName: String) {
        windowsProbes[name]?.cancel()
        windowsProbes[name] = Task {
            let address = ComputerAddress(host: hostName, port: RemoteProtocol.rdp.defaultPort)
            let answer = await AvailabilityProbe.check(address, remoteProtocol: .rdp)
            guard !Task.isCancelled else { return }
            windowsProbes[name] = nil
            let index = computers.firstIndex { $0.remoteProtocol == .rdp && $0.name == name }
            guard answer != .nothing else {
                if let index { computers.remove(at: index) }
                return
            }
            var computer = index.map { computers[$0] } ?? NearbyComputer(name: name, remoteProtocol: .rdp)
            computer.hostName = hostName
            computer.port = address.port
            if let index {
                computers[index] = computer
            } else {
                computers.append(computer)
                computers.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            }
        }
    }

    func stop() {
        guard !isFixed else { return }
        activeClients = max(0, activeClients - 1)
        guard activeClients == 0 else { return }
        restart?.cancel()
        restart = nil
        search?.cancel()
        search = nil
        cancelBrowsing()
        state = .idle
        hasSearched = false
    }

    private func cancelBrowsing() {
        currentResults.removeAll()
        computers.removeAll()
        browser?.cancel()
        browser = nil
        windowsBrowser?.cancel()
        windowsBrowser = nil
        resolvers.values.forEach { $0.cancel() }
        resolvers.removeAll()
        windowsResolvers.values.forEach { $0.cancel() }
        windowsResolvers.removeAll()
        windowsProbes.values.forEach { $0.cancel() }
        windowsProbes.removeAll()
        models.removeAll()
        serviceModels.removeAll()
    }

    private func restartBrowsing() {
        restart?.cancel()
        restart = Task {
            try? await Task.sleep(seconds: Self.restartDelay)
            guard !Task.isCancelled else { return }
            cancelBrowsing()
            browse()
        }
    }

    func hasCurrentService(matching address: ComputerAddress, remoteProtocol: RemoteProtocol) -> Bool {
        state == .browsing && currentResults.contains(remoteProtocol)
            && computer(matching: address, remoteProtocol: remoteProtocol) != nil
    }

    func computer(matching address: ComputerAddress, remoteProtocol: RemoteProtocol) -> NearbyComputer? {
        computers.first {
            $0.remoteProtocol == remoteProtocol && ($0.port == nil || $0.port == address.port) && Self.matches($0, address)
        }
    }

    func host(matching address: ComputerAddress) -> NearbyComputer? {
        computers.first { Self.matches($0, address) }
    }

    func lacksHost(matching address: ComputerAddress) -> Bool {
        state == .browsing && currentResults.contains(.vnc)
            && !computers.contains { $0.remoteProtocol == .vnc && $0.hostName == nil }
            && host(matching: address) == nil
    }

    private static func matches(_ computer: NearbyComputer, _ address: ComputerAddress) -> Bool {
        let host = normalized(address.host)
        if let hostName = computer.hostName, normalized(hostName) == host { return true }
        if let raw = rawAddress(of: address.host), computer.addresses.contains(raw) { return true }
        return normalized(computer.name) == host || address.bonjourServiceName == computer.name
    }

    private static func normalized(_ host: String) -> String {
        var value = host.lowercased()
        if value.hasSuffix(".") { value.removeLast() }
        if value.hasSuffix(".local") { value.removeLast(6) }
        return value
    }

    private static func rawAddress(of host: String) -> Data? {
        IPv4Address(host)?.rawValue ?? IPv6Address(host)?.rawValue
    }

    private func handle(_ newState: NWBrowser.State) {
        switch newState {
        case .failed(.dns(let code))
        where isReady && (code == kDNSServiceErr_DefunctConnection || code == kDNSServiceErr_ServiceNotRunning):
            restartBrowsing()
        case .failed(let error), .waiting(let error):
            state = .failed(error.localizedDescription)
        case .ready:
            isReady = true
            state = .browsing
        default:
            break
        }
    }

    private func update(model: String, for name: String) {
        guard models[name] != model else { return }
        models[name] = model
        guard let index = computers.firstIndex(where: { $0.remoteProtocol == .vnc && $0.name == name }) else { return }
        computers[index].model = self.model(of: name)
    }

    private func model(of name: String) -> String? {
        serviceModels[name] ?? models[name]
    }

    private func update(names: [String], models: [String: String] = [:]) {
        currentResults.insert(.vnc)
        serviceModels = models
        let unique = Set(names)
        for name in resolvers.keys where !unique.contains(name) {
            resolvers[name]?.cancel()
            resolvers[name] = nil
            self.models[name] = nil
        }
        let macs = unique.map { name in
            var computer = computers.first { $0.remoteProtocol == .vnc && $0.name == name } ?? NearbyComputer(name: name)
            computer.model = model(of: name)
            return computer
        }
        computers = (macs + computers.filter { $0.remoteProtocol == .rdp }).sorted {
            $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
        for name in unique where resolvers[name] == nil {
            let resolver = ServiceResolver(name: name) { [weak self] hostName, port in
                guard let self, let index = computers.firstIndex(where: { $0.remoteProtocol == .vnc && $0.name == name }) else { return }
                computers[index].hostName = hostName
                computers[index].port = port
            } addresses: { [weak self] addresses in
                guard let self, let index = computers.firstIndex(where: { $0.remoteProtocol == .vnc && $0.name == name }) else { return }
                computers[index].addresses = addresses
            } model: { [weak self] model in
                self?.update(model: model, for: name)
            }
            resolvers[name] = resolver
            resolver.start()
        }
    }
}

final class ServiceResolver {
    nonisolated private static let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "OpenScreenSharing", category: "discovery")

    nonisolated struct Failure: LocalizedError {
        let code: DNSServiceErrorType

        var errorDescription: String? {
            "Bonjour: \(NSError(domain: NSOSStatusErrorDomain, code: Int(code)).localizedDescription)"
        }
    }

    private let name: String
    private let type: String
    private let resolved: (String, Int) -> Void
    private let addressesChanged: ((Set<Data>) -> Void)?
    private let modelChanged: ((String) -> Void)?
    private let failed: ((Error) -> Void)?
    private var cancelled = false
    private var reference: DNSServiceRef?
    private var addressReference: DNSServiceRef?
    private var modelReference: DNSServiceRef?
    private var addressHost: String?
    private var addresses: Set<Data> = []

    init(
        name: String, type: String = "_rfb._tcp", resolved: @escaping (String, Int) -> Void,
        addresses: ((Set<Data>) -> Void)? = nil, model: ((String) -> Void)? = nil, failed: ((Error) -> Void)? = nil
    ) {
        self.name = name
        self.type = type
        self.resolved = resolved
        addressesChanged = addresses
        modelChanged = model
        self.failed = failed
    }

    func start() {
        guard !cancelled, reference == nil else { return }
        let context = Unmanaged.passUnretained(self).toOpaque()
        var serviceReference: DNSServiceRef?
        let error = DNSServiceResolve(
            &serviceReference, 0, 0, name, type, "local.",
            { _, _, _, errorCode, _, hostTarget, port, _, _, context in
                guard let context else { return }
                let resolver = Unmanaged<ServiceResolver>.fromOpaque(context).takeUnretainedValue()
                MainActor.assumeIsolated {
                    guard errorCode == kDNSServiceErr_NoError, let hostTarget else {
                        resolver.fail(errorCode == kDNSServiceErr_NoError ? DNSServiceErrorType(kDNSServiceErr_BadParam) : errorCode)
                        return
                    }
                    let qualifiedHostName = String(cString: hostTarget)
                    var hostName = qualifiedHostName
                    if hostName.hasSuffix(".") { hostName.removeLast() }
                    let hostPort = Int(UInt16(bigEndian: port))
                    resolver.lookUpAddresses(of: qualifiedHostName)
                    resolver.resolved(hostName, hostPort)
                }
            }, context)
        guard error == kDNSServiceErr_NoError, let serviceReference else {
            fail(error == kDNSServiceErr_NoError ? DNSServiceErrorType(kDNSServiceErr_BadReference) : error)
            return
        }
        reference = serviceReference
        let dispatchError = DNSServiceSetDispatchQueue(serviceReference, .main)
        guard dispatchError == kDNSServiceErr_NoError else {
            fail(dispatchError)
            return
        }
        lookUpModel()
    }

    /// A Mac answered a named _device-info._tcp TXT query with its model while a service browse found nothing
    /// (observed on macOS 27.0.1 26A434).
    private func lookUpModel() {
        guard modelChanged != nil else { return }
        var fullName = [CChar](repeating: 0, count: Int(kDNSServiceMaxDomainName))
        let nameError = DNSServiceConstructFullName(&fullName, name, "_device-info._tcp", "local.")
        guard nameError == kDNSServiceErr_NoError else {
            Self.logger.error("Model query name failed: \(nameError)")
            return
        }
        let context = Unmanaged.passUnretained(self).toOpaque()
        var serviceReference: DNSServiceRef?
        let error = DNSServiceQueryRecord(
            &serviceReference, 0, 0, fullName, UInt16(kDNSServiceType_TXT), UInt16(kDNSServiceClass_IN),
            { _, flags, _, errorCode, _, _, _, length, data, _, context in
                guard let context else { return }
                let resolver = Unmanaged<ServiceResolver>.fromOpaque(context).takeUnretainedValue()
                guard errorCode == kDNSServiceErr_NoError else {
                    ServiceResolver.logger.error("Model query failed: \(errorCode)")
                    MainActor.assumeIsolated {
                        if let reference = resolver.modelReference { DNSServiceRefDeallocate(reference) }
                        resolver.modelReference = nil
                    }
                    return
                }
                guard flags & DNSServiceFlags(kDNSServiceFlagsAdd) != 0, let data else { return }
                var valueLength: UInt8 = 0
                guard let value = TXTRecordGetValuePtr(length, data, "model", &valueLength), valueLength > 0 else { return }
                let model = String(decoding: UnsafeRawBufferPointer(start: value, count: Int(valueLength)), as: UTF8.self)
                MainActor.assumeIsolated {
                    resolver.modelChanged?(model)
                }
            }, context)
        guard error == kDNSServiceErr_NoError, let serviceReference else {
            Self.logger.error("Model query failed: \(error)")
            return
        }
        modelReference = serviceReference
        let dispatchError = DNSServiceSetDispatchQueue(serviceReference, .main)
        if dispatchError != kDNSServiceErr_NoError {
            Self.logger.error("Model query dispatch failed: \(dispatchError)")
            DNSServiceRefDeallocate(serviceReference)
            modelReference = nil
        }
    }

    private func lookUpAddresses(of host: String) {
        guard addressesChanged != nil, addressHost != host else { return }
        if let addressReference {
            DNSServiceRefDeallocate(addressReference)
        }
        addressReference = nil
        addressHost = host
        addresses = []
        let context = Unmanaged.passUnretained(self).toOpaque()
        var serviceReference: DNSServiceRef?
        let error = DNSServiceGetAddrInfo(
            &serviceReference, 0, 0, DNSServiceProtocol(kDNSServiceProtocol_IPv4 | kDNSServiceProtocol_IPv6), host,
            { _, flags, _, errorCode, _, address, _, context in
                guard let context else { return }
                let resolver = Unmanaged<ServiceResolver>.fromOpaque(context).takeUnretainedValue()
                guard errorCode == kDNSServiceErr_NoError else {
                    ServiceResolver.logger.error("Address query failed: \(errorCode)")
                    MainActor.assumeIsolated {
                        if let reference = resolver.addressReference { DNSServiceRefDeallocate(reference) }
                        resolver.addressReference = nil
                        resolver.addressHost = nil
                        resolver.addresses = []
                        resolver.addressesChanged?([])
                    }
                    return
                }
                guard let address, let raw = ServiceResolver.rawAddress(address) else { return }
                let added = flags & DNSServiceFlags(kDNSServiceFlagsAdd) != 0
                let moreComing = flags & DNSServiceFlags(kDNSServiceFlagsMoreComing) != 0
                MainActor.assumeIsolated {
                    if added {
                        resolver.addresses.insert(raw)
                    } else {
                        resolver.addresses.remove(raw)
                    }
                    if !moreComing {
                        resolver.addressesChanged?(resolver.addresses)
                    }
                }
            }, context)
        guard error == kDNSServiceErr_NoError, let serviceReference else {
            Self.logger.error("Address query failed: \(error)")
            return
        }
        addressReference = serviceReference
        let dispatchError = DNSServiceSetDispatchQueue(serviceReference, .main)
        if dispatchError != kDNSServiceErr_NoError {
            Self.logger.error("Address query dispatch failed: \(dispatchError)")
            DNSServiceRefDeallocate(serviceReference)
            addressReference = nil
        }
    }

    nonisolated static func rawAddress(_ address: UnsafePointer<sockaddr>) -> Data? {
        switch Int32(address.pointee.sa_family) {
        case AF_INET:
            return address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { pointer in
                withUnsafeBytes(of: pointer.pointee.sin_addr) { Data($0) }
            }
        case AF_INET6:
            return address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { pointer in
                withUnsafeBytes(of: pointer.pointee.sin6_addr) { Data($0) }
            }
        default:
            return nil
        }
    }

    private func fail(_ code: DNSServiceErrorType) {
        guard !cancelled else { return }
        let error = Failure(code: code)
        Self.logger.error("Service resolution failed: \(error.localizedDescription, privacy: .public)")
        cancel()
        failed?(error)
    }

    func cancel() {
        cancelled = true
        if let reference {
            DNSServiceRefDeallocate(reference)
        }
        reference = nil
        if let addressReference {
            DNSServiceRefDeallocate(addressReference)
        }
        addressReference = nil
        if let modelReference {
            DNSServiceRefDeallocate(modelReference)
        }
        modelReference = nil
    }

    static func resolve(name: String) async throws -> ComputerAddress {
        let resolution = Resolution(name: name)
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                resolution.start(continuation)
            }
        } onCancel: {
            Task { @MainActor in resolution.finish(.failure(CancellationError())) }
        }
    }

    private final class Resolution {
        private let name: String
        private var resolver: ServiceResolver?
        private var continuation: CheckedContinuation<ComputerAddress, Error>?
        private var finished = false

        init(name: String) {
            self.name = name
        }

        func start(_ continuation: CheckedContinuation<ComputerAddress, Error>) {
            guard !finished else {
                continuation.resume(throwing: CancellationError())
                return
            }
            self.continuation = continuation
            let resolver = ServiceResolver(name: name) { [weak self] host, port in
                self?.finish(.success(ComputerAddress(host: host, port: port)))
            } failed: { [weak self] error in
                self?.finish(.failure(error))
            }
            self.resolver = resolver
            resolver.start()
        }

        func finish(_ result: Result<ComputerAddress, Error>) {
            guard !finished else { return }
            finished = true
            resolver?.cancel()
            resolver = nil
            let continuation = continuation
            self.continuation = nil
            continuation?.resume(with: result)
        }
    }
}
