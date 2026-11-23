import Foundation
import Network
import Perception
import dnssd

nonisolated enum ComputerAvailability: Hashable, Sendable {
    case available
    case unavailable
}

/// With its blacklist enabled, TigerVNC counts banner-only connections until authentication succeeds
/// (tigervnc 086c692, common/rfb/Blacklist.cxx:52-89, common/rfb/VNCServerST.cxx:141-161,
/// common/rfb/VNCServerST.cxx:741-745).
@Perceptible
final class AvailabilityMonitor {
    private(set) var availability: [ComputerAddress: ComputerAvailability] = [:]

    private let nearby: NearbyBrowser
    private var servers: [ComputerAddress: RemoteProtocol] = [:]
    @PerceptionIgnored private var probes: [ComputerAddress: Task<Void, Never>] = [:]
    @PerceptionIgnored private var activeClients = 0
    @PerceptionIgnored private var pingedServers: Set<String>
    private static let pingedServersKey = "availabilityPingedServers"
    private static let refreshInterval: TimeInterval = 1
    private static let remoteRefreshInterval: TimeInterval = 5

    init(nearby: NearbyBrowser) {
        self.nearby = nearby
        pingedServers = Set(PreferenceStore.shared.value([String].self, for: Self.pingedServersKey) ?? [])
    }

    func availability(of computer: Computer) -> ComputerAvailability? {
        var hasUnknown = false
        for endpoint in computer.endpoints {
            switch availability(of: endpoint.address) {
            case .available:
                return .available
            case nil:
                hasUnknown = true
            case .unavailable:
                break
            }
        }
        return hasUnknown ? nil : .unavailable
    }

    func availability(of address: ComputerAddress) -> ComputerAvailability? {
        if servers[address] == .vnc, nearby.hasCurrentService(matching: address, remoteProtocol: .vnc) {
            return .available
        }
        if servers[address] == .vnc, address.isLocal, nearby.lacksHost(matching: address) {
            return .unavailable
        }
        return availability[address]
    }

    func start() {
        activeClients += 1
        guard activeClients == 1 else { return }
        for address in servers.keys {
            probe(address)
        }
    }

    func stop() {
        activeClients = max(0, activeClients - 1)
        guard activeClients == 0 else { return }
        probes.values.forEach { $0.cancel() }
        probes.removeAll()
        availability.removeAll()
    }

    func update(servers: [ComputerAddress: RemoteProtocol]) {
        for address in self.servers.keys where servers[address] == nil {
            probes[address]?.cancel()
            probes[address] = nil
            availability[address] = nil
        }
        let changed = servers.keys.filter { self.servers[$0] != servers[$0] }
        self.servers = servers
        guard activeClients > 0 else { return }
        for address in changed {
            availability[address] = nil
            probe(address)
        }
    }

    private func probe(_ address: ComputerAddress) {
        probes[address]?.cancel()
        probes[address] = Task {
            while !Task.isCancelled {
                let result = await check(address)
                guard !Task.isCancelled else { return }
                if availability[address] != result {
                    availability[address] = result
                }
                let interval = address.kind == .privateNetwork ? Self.refreshInterval : Self.remoteRefreshInterval
                try? await Task.sleep(seconds: interval)
            }
        }
    }

    private func check(_ address: ComputerAddress) async -> ComputerAvailability? {
        if servers[address] == .rdp {
            return await AvailabilityProbe.check(address, remoteProtocol: .rdp) == .nothing ? .unavailable : .available
        }
        if nearby.hasCurrentService(matching: address, remoteProtocol: .vnc) {
            return .available
        }
        if address.isLocal, nearby.lacksHost(matching: address) {
            return .unavailable
        }
        if pingedServers.contains(address.serverKey) {
            return await PingProbe.check(address.host)
        }
        switch await AvailabilityProbe.check(address, remoteProtocol: .vnc) {
        case .mac:
            return .available
        case .otherServer:
            rememberPingedServer(address)
            return .available
        case .nothing:
            return .unavailable
        }
    }

    private func rememberPingedServer(_ address: ComputerAddress) {
        guard pingedServers.insert(address.serverKey).inserted else { return }
        PreferenceStore.shared.set(pingedServers.sorted(), for: Self.pingedServersKey)
    }
}

extension ComputerAddress {
    fileprivate var isLocal: Bool {
        if bonjourServiceName != nil { return true }
        let lowered = host.lowercased()
        return lowered.hasSuffix(".local") || lowered.hasSuffix(".local.")
    }

    fileprivate var serverKey: String {
        "\(host.lowercased()):\(port)"
    }
}

nonisolated enum AvailabilityProbe {
    enum Answer: Sendable {
        /// Apple's Screen Sharing, which announces RFB 3.889 (screensharingd 756.36.5.2, macOS 27.0.1 26A434,
        /// UUID 44908B06-F788-3822-ABC4-2285001F6A8D, sub_10002fcb4 0x10003061c..0x10003062c).
        case mac
        case otherServer
        case nothing
    }

    private static let queue = DispatchQueue(label: "io.nekohasekai.screensharing.availability", qos: .utility)
    private static let connectionTimeout = 15

    static func check(_ address: ComputerAddress, remoteProtocol: RemoteProtocol) async -> Answer {
        let attempt = Attempt(address: address, remoteProtocol: remoteProtocol)
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                attempt.start(continuation)
            }
        } onCancel: {
            attempt.cancel()
        }
    }

    private static func answer(for banner: Data?) -> Answer {
        guard let banner, banner.starts(with: "RFB ".utf8) else { return .nothing }
        return banner.starts(with: "RFB 003.889".utf8) ? .mac : .otherServer
    }

    private final class Attempt: @unchecked Sendable {
        private let connection: NWConnection
        private let remoteProtocol: RemoteProtocol
        private var continuation: CheckedContinuation<Answer, Never>?
        private var finished = false

        init(address: ComputerAddress, remoteProtocol: RemoteProtocol) {
            self.remoteProtocol = remoteProtocol
            let tcp = NWProtocolTCP.Options()
            tcp.connectionTimeout = AvailabilityProbe.connectionTimeout
            connection = NWConnection(
                host: NWEndpoint.Host(address.host),
                port: NWEndpoint.Port(integerLiteral: UInt16(clamping: address.port)),
                using: NWParameters(tls: nil, tcp: tcp)
            )
        }

        func start(_ continuation: CheckedContinuation<Answer, Never>) {
            AvailabilityProbe.queue.async { [self] in
                guard !finished else {
                    continuation.resume(returning: .nothing)
                    return
                }
                self.continuation = continuation
                connection.stateUpdateHandler = { [self] state in
                    switch state {
                    case .ready where remoteProtocol == .rdp:
                        finish(.otherServer)
                    case .ready:
                        connection.receive(minimumIncompleteLength: 12, maximumLength: 12) { [self] data, _, _, _ in
                            finish(AvailabilityProbe.answer(for: data))
                        }
                    case .waiting, .failed, .cancelled:
                        finish(.nothing)
                    default:
                        break
                    }
                }
                connection.start(queue: AvailabilityProbe.queue)
            }
        }

        func cancel() {
            AvailabilityProbe.queue.async { [self] in finish(.nothing) }
        }

        private func finish(_ answer: Answer) {
            guard !finished else { return }
            finished = true
            connection.stateUpdateHandler = nil
            connection.cancel()
            let continuation = continuation
            self.continuation = nil
            continuation?.resume(returning: answer)
        }
    }
}

nonisolated enum PingProbe {
    private static let queue = DispatchQueue(label: "io.nekohasekai.screensharing.ping", qos: .utility)
    private static let retryInterval: TimeInterval = 1
    private static let identifier = UInt16.random(in: 1...UInt16.max)
    private static let sequence = LockedState(initialState: UInt16(0))

    static func check(_ host: String) async -> ComputerAvailability? {
        let attempt = Attempt(host: host)
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                attempt.start(continuation)
            }
        } onCancel: {
            attempt.cancel()
        }
    }

    private final class Attempt: @unchecked Sendable {
        private struct Socket {
            let descriptor: Int32
            let reader: DispatchSourceRead
            let address: Data
            let request: [UInt8]
            let sequence: UInt16
            let isIPv6: Bool
        }

        private let host: String
        private var continuation: CheckedContinuation<ComputerAvailability?, Never>?
        private var finished = false
        private var reference: DNSServiceRef?
        private var sockets: [Data: Socket] = [:]
        private var unansweredFamilies: Set<Int32> = []
        private var moreComing = false
        private var unreachable = false
        private var inconclusive = false
        private var retry: DispatchSourceTimer?

        init(host: String) {
            self.host = host
        }

        func start(_ continuation: CheckedContinuation<ComputerAvailability?, Never>) {
            PingProbe.queue.async { [self] in
                guard !finished else {
                    continuation.resume(returning: nil)
                    return
                }
                self.continuation = continuation
                var hints = addrinfo()
                hints.ai_flags = AI_NUMERICHOST
                hints.ai_socktype = SOCK_DGRAM
                var list: UnsafeMutablePointer<addrinfo>?
                if getaddrinfo(host, nil, &hints, &list) == 0, let list {
                    defer { freeaddrinfo(list) }
                    add(list.pointee.ai_addr)
                    guard !sockets.isEmpty else {
                        finish(nil)
                        return
                    }
                } else {
                    // With protocol 0, mDNSResponder queries both A and AAAA with SuppressUnusable and, with
                    // ReturnIntermediates, answers a suppressed family negatively (mDNSResponder-2881.0.25,
                    // mDNSShared/ClientRequests.c:117-121, mDNSCore/mDNS.c:5482-5494).
                    unansweredFamilies = [AF_INET, AF_INET6]
                    let error = DNSServiceGetAddrInfo(
                        &reference, DNSServiceFlags(kDNSServiceFlagsReturnIntermediates), 0, 0, host,
                        { _, flags, _, error, _, address, _, context in
                            guard let context else { return }
                            let attempt = Unmanaged<Attempt>.fromOpaque(context).takeUnretainedValue()
                            guard let address, error == kDNSServiceErr_NoError || error == kDNSServiceErr_NoSuchRecord else {
                                attempt.finish(nil)
                                return
                            }
                            attempt.moreComing = flags & DNSServiceFlags(kDNSServiceFlagsMoreComing) != 0
                            attempt.unansweredFamilies.remove(Int32(address.pointee.sa_family))
                            if error == kDNSServiceErr_NoError {
                                if flags & DNSServiceFlags(kDNSServiceFlagsAdd) != 0 {
                                    attempt.add(address)
                                } else {
                                    let key = Data(bytes: address, count: Int(address.pointee.sa_len))
                                    attempt.sockets.removeValue(forKey: key)?.reader.cancel()
                                }
                            }
                            attempt.settle()
                        }, Unmanaged.passUnretained(self).toOpaque())
                    guard error == kDNSServiceErr_NoError, let reference else {
                        finish(nil)
                        return
                    }
                    guard DNSServiceSetDispatchQueue(reference, PingProbe.queue) == kDNSServiceErr_NoError else {
                        finish(nil)
                        return
                    }
                }
                let retry = DispatchSource.makeTimerSource(queue: PingProbe.queue)
                retry.schedule(deadline: .now() + PingProbe.retryInterval, repeating: PingProbe.retryInterval)
                retry.setEventHandler { [self] in
                    for key in Array(sockets.keys) {
                        send(key)
                    }
                }
                self.retry = retry
                retry.resume()
            }
        }

        private func add(_ address: UnsafePointer<sockaddr>) {
            guard !finished else { return }
            let family = Int32(address.pointee.sa_family)
            guard family == AF_INET || family == AF_INET6 else { return }
            let key = Data(bytes: address, count: Int(address.pointee.sa_len))
            guard sockets[key] == nil else { return }
            let isIPv6 = family == AF_INET6
            let descriptor = Darwin.socket(family, SOCK_DGRAM, isIPv6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP)
            guard descriptor >= 0 else {
                inconclusive = true
                settle()
                return
            }
            guard fcntl(descriptor, F_SETFL, O_NONBLOCK) == 0 else {
                close(descriptor)
                inconclusive = true
                settle()
                return
            }
            let sequence = PingProbe.sequence.withLock { value in
                value &+= 1
                return value
            }
            var request = [UInt8](repeating: 0, count: 64)
            request[0] = isIPv6 ? 128 : 8
            request[4] = UInt8(PingProbe.identifier >> 8)
            request[5] = UInt8(PingProbe.identifier & 0xff)
            request[6] = UInt8(sequence >> 8)
            request[7] = UInt8(sequence & 0xff)
            for index in 8..<request.count {
                request[index] = UInt8(truncatingIfNeeded: index)
            }
            if !isIPv6 {
                let sum = PingProbe.checksum(request)
                request[2] = UInt8(sum >> 8)
                request[3] = UInt8(sum & 0xff)
            }
            let reader = DispatchSource.makeReadSource(fileDescriptor: descriptor, queue: PingProbe.queue)
            reader.setCancelHandler { close(descriptor) }
            reader.setEventHandler { [self] in read(key) }
            sockets[key] = Socket(
                descriptor: descriptor, reader: reader, address: key, request: request, sequence: sequence, isIPv6: isIPv6)
            reader.resume()
            send(key)
        }

        private func send(_ key: Data) {
            guard let socket = sockets[key] else { return }
            let sent = socket.address.withUnsafeBytes { address in
                sendto(socket.descriptor, socket.request, socket.request.count, 0,
                    address.baseAddress!.assumingMemoryBound(to: sockaddr.self), socklen_t(address.count))
            }
            guard sent < 0 else { return }
            switch errno {
            case EAGAIN, EWOULDBLOCK, EINTR:
                return
            case EHOSTDOWN, EHOSTUNREACH, ENETUNREACH, ENETDOWN:
                unreachable = true
            default:
                inconclusive = true
            }
            sockets.removeValue(forKey: key)?.reader.cancel()
            settle()
        }

        private func settle() {
            guard sockets.isEmpty, !moreComing, unreachable || unansweredFamilies.isEmpty else { return }
            finish(inconclusive ? nil : .unavailable)
        }

        private func read(_ key: Data) {
            guard let socket = sockets[key], !finished else { return }
            var reply = [UInt8](repeating: 0, count: 2048)
            while true {
                let received = recv(socket.descriptor, &reply, reply.count, 0)
                guard received > 0 else {
                    if received < 0, errno == EINTR { continue }
                    if received == 0 || (errno != EAGAIN && errno != EWOULDBLOCK) {
                        inconclusive = true
                        sockets.removeValue(forKey: key)?.reader.cancel()
                        settle()
                    }
                    return
                }
                var offset = 0
                if !socket.isIPv6 {
                    guard received >= 20 else { continue }
                    offset = Int(reply[0] & 0x0f) * 4
                    guard offset >= 20 else { continue }
                }
                guard received >= offset + 8, reply[offset] == (socket.isIPv6 ? 129 : 0) else { continue }
                let identifier = UInt16(reply[offset + 4]) << 8 | UInt16(reply[offset + 5])
                let sequence = UInt16(reply[offset + 6]) << 8 | UInt16(reply[offset + 7])
                if identifier == PingProbe.identifier, sequence == socket.sequence {
                    finish(.available)
                    return
                }
            }
        }

        func cancel() {
            PingProbe.queue.async { [self] in finish(nil) }
        }

        private func finish(_ result: ComputerAvailability?) {
            guard !finished else { return }
            finished = true
            if let reference {
                DNSServiceRefDeallocate(reference)
            }
            reference = nil
            retry?.cancel()
            retry = nil
            sockets.values.forEach { $0.reader.cancel() }
            sockets.removeAll()
            let continuation = continuation
            self.continuation = nil
            continuation?.resume(returning: result)
        }
    }

    private static func checksum(_ bytes: [UInt8]) -> UInt16 {
        var sum: UInt32 = 0
        var index = 0
        while index + 1 < bytes.count {
            sum += UInt32(bytes[index]) << 8 | UInt32(bytes[index + 1])
            index += 2
        }
        if index < bytes.count {
            sum += UInt32(bytes[index]) << 8
        }
        while sum >> 16 != 0 {
            sum = (sum & 0xffff) + (sum >> 16)
        }
        return ~UInt16(sum)
    }
}
