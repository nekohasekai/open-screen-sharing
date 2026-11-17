import Foundation
import GRDB
#if DEBUG || DEMO
import Perception
#endif
#if (DEBUG || DEMO) && os(iOS)
import Darwin
import SwiftUI
import UIKit
#endif

struct Demo {
    let database: DatabaseQueue
    let nearby: NearbyBrowser
    let makeSession: (SessionController, SessionEventSink) -> any RemoteSession
    #if (DEBUG || DEMO) && os(iOS)
    private let state: BatchState?
    #endif

    static let current: Demo? = {
        #if DEBUG || DEMO
        ProcessInfo.processInfo.arguments.contains("-Demo") ? Demo() : nil
        #else
        nil
        #endif
    }()
}

#if DEBUG || DEMO
extension Demo {
    #if os(iOS)
    private final class BatchState {
        weak var availabilityMonitor: AvailabilityMonitor?
    }

    private final class FrameWaiter: NSObject {
        private var remaining: Int
        private var continuation: CheckedContinuation<Void, Never>?
        private var link: CADisplayLink?

        init(frames: Int) {
            self.remaining = frames
            super.init()
        }

        func wait() async {
            await withCheckedContinuation { continuation in
                self.continuation = continuation
                let link = CADisplayLink(target: self, selector: #selector(tick))
                self.link = link
                link.add(to: .main, forMode: .common)
            }
        }

        @objc private func tick() {
            remaining -= 1
            if remaining <= 0 {
                link?.invalidate()
                link = nil
                let continuation = self.continuation
                self.continuation = nil
                continuation?.resume()
            }
        }
    }

    func attach(availabilityMonitor: AvailabilityMonitor) {
        state?.availabilityMonitor = availabilityMonitor
    }
    #endif

    nonisolated private struct SavedComputer {
        var name: String
        var model: String
        var lastConnected: TimeInterval
        var thumbnail: VirtualDisplayMode

        var host: String { name.replacingOccurrences(of: " ", with: "-") + ".local" }
    }

    private enum Page: String {
        case session = "01-Session"
        case connections = "02-Connections"
    }

    private static let saved = [
        SavedComputer(name: "Mac mini", model: "Mac16,10", lastConnected: 12 * 60, thumbnail: VirtualDisplayMode(width: 1280, height: 800, density: 2))
    ]

    private init() {
        let page = UserDefaults.standard.string(forKey: "DemoScreenshot").map { Page(rawValue: $0)! }
        let frames = UserDefaults.standard.string(forKey: "DemoFrames").map { DemoFrames(directory: URL(fileURLWithPath: $0, isDirectory: true)) }
        #if os(iOS)
        let controlPort = UserDefaults.standard.integer(forKey: "DemoControlPort")
        let batchState = controlPort > 0 ? BatchState() : nil
        state = batchState
        #endif
        database = try! DatabaseQueue()
        try! AppDatabase.migrator.migrate(database)
        var computers: [Computer] = []
        for (index, saved) in Self.saved.enumerated() {
            let address = ComputerAddress(host: saved.host)
            var computer = Computer(name: saved.name, address: address, username: "demo")
            computer.password = "demo"
            computer.authenticates = true
            computer.usesHighPerformance = true
            computer.model = saved.model
            computer.lastConnected = Date(timeIntervalSinceNow: -saved.lastConnected)
            computer.createdAt = Date(timeIntervalSinceNow: TimeInterval(index - Self.saved.count))
            try! database.write { try computer.insert($0) }
            computers.append(computer)
        }
        nearby = NearbyBrowser(
            fixed: zip(Self.saved, computers).map { saved, computer in
                NearbyComputer(name: saved.name, hostName: saved.host, port: computer.port, model: saved.model)
            })
        makeSession = { controller, sink in
            if page == .session {
                Task {
                    await Self.until { controller.picture == .current && controller.isVideo == controller.usesHighPerformance }
                    print(Page.session.rawValue)
                }
            }
            return DemoRemoteSession(sink: sink, frames: frames)
        }
        let database = database
        let thumbnails = Task {
            guard let frames, page == nil || page == .connections else { return }
            await withTaskGroup(of: Void.self) { group in
                for (saved, computer) in zip(Self.saved, computers) {
                    let id = computer.id
                    group.addTask {
                        guard let url = await frames.frame(saved.thumbnail), let thumbnail = try? Data(contentsOf: url) else { return }
                        _ = try! await database.write { try Computer.filter(key: id).updateAll($0, Column("thumbnail").set(to: thumbnail)) }
                    }
                }
            }
        }
        switch page {
        case nil:
            break
        case .connections:
            let nearbyBrowser = nearby
            Task {
                #if os(iOS)
                let activations = NotificationCenter.default.notifications(named: UIScene.didActivateNotification)
                if !UIApplication.shared.connectedScenes.contains(where: { $0.activationState == .foregroundActive }) {
                    for await _ in activations { break }
                }
                await thumbnails.value
                if controlPort > 0, let batchState {
                    await Self.runConnectionsBatch(
                        port: UInt16(controlPort),
                        nearby: nearbyBrowser,
                        state: batchState
                    )
                    return
                }
                #else
                await thumbnails.value
                #endif
                print(Page.connections.rawValue)
            }
        case .session:
            HomeRequest.shared.pending = .connect(SessionRoute(computer: computers[0]))
        }
    }

    private static func until(_ condition: () -> Bool) async {
        while !condition() {
            await withCheckedContinuation { continuation in
                withPerceptionTracking {
                    _ = condition()
                } onChange: {
                    continuation.resume()
                }
            }
        }
    }

    #if os(iOS)
    private static func runConnectionsBatch(port: UInt16, nearby: NearbyBrowser, state: BatchState) async {
        let fd = await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let sock = Darwin.socket(AF_INET, SOCK_STREAM, 0)
                guard sock >= 0 else {
                    continuation.resume(returning: Int32(-1))
                    return
                }
                var addr = sockaddr_in()
                addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                addr.sin_family = sa_family_t(AF_INET)
                addr.sin_port = port.bigEndian
                addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
                let connected = withUnsafePointer(to: &addr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        Darwin.connect(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
                guard connected == 0 else {
                    Darwin.close(sock)
                    continuation.resume(returning: Int32(-1))
                    return
                }
                continuation.resume(returning: sock)
            }
        }
        guard fd >= 0 else { return }
        defer { Darwin.close(fd) }

        while let language = await readLine(from: fd) {
            if language == "quit" { break }
            applyConnectionsLanguage(language, nearby: nearby, state: state)
            await FrameWaiter(frames: 2).wait()
            let ack = Data("ok\n".utf8)
            _ = ack.withUnsafeBytes { bytes in
                Darwin.send(fd, bytes.baseAddress, bytes.count, 0)
            }
        }
    }

    nonisolated private static func readLine(from fd: Int32) async -> String? {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                var line = Data()
                var byte: UInt8 = 0
                while true {
                    let count = Darwin.recv(fd, &byte, 1, 0)
                    if count <= 0 {
                        continuation.resume(returning: nil)
                        return
                    }
                    if byte == 0x0A {
                        let text = String(data: line, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
                        continuation.resume(returning: text)
                        return
                    }
                    line.append(byte)
                }
            }
        }
    }

    private static func applyConnectionsLanguage(_ language: String, nearby: NearbyBrowser, state: BatchState) {
        guard let availabilityMonitor = state.availabilityMonitor else { return }
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive })
            ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first
        guard let window = scene?.windows.first(where: \.isKeyWindow) ?? scene?.windows.first else { return }
        let locale = Locale(identifier: language)
        let isRTL: Bool = if #available(iOS 16, *) {
            Locale.Language(identifier: language).characterDirection == .rightToLeft
        } else {
            Locale.characterDirection(forLanguage: locale.languageCode ?? language) == .rightToLeft
        }
        UIView.performWithoutAnimation {
            UIView.appearance().semanticContentAttribute = isRTL ? .forceRightToLeft : .forceLeftToRight
            window.semanticContentAttribute = isRTL ? .forceRightToLeft : .forceLeftToRight
            let root = HomeView()
                .providesSessionWindows()
                .environment(nearby)
                .environment(availabilityMonitor)
                .environment(\.locale, locale)
                .environment(\.layoutDirection, isRTL ? .rightToLeft : .leftToRight)
            window.rootViewController = UIHostingController(rootView: root)
            window.layoutIfNeeded()
            CATransaction.flush()
        }
    }
    #endif
}
#endif
