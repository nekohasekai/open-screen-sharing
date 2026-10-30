#if os(macOS)
import AppKit
import Observation
import SwiftUI
import os

@Observable
final class UpdateManager {
    static let shared = UpdateManager()

    nonisolated private static let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "OpenScreenSharing", category: "update")
    private static let installedApp = URL(filePath: "/Applications/Open Screen Sharing.app")

    private enum Key {
        static let checksAutomatically = "checksForUpdatesAutomatically"
        static let githubToken = "githubToken"
        static let track = "updateTrack"
        static let presentedBuild = "presentedUpdateBuild"
    }

    enum Phase {
        case idle
        case downloading
        case installing
    }

    var checksAutomatically: Bool {
        didSet { store.set(checksAutomatically, for: Key.checksAutomatically) }
    }

    var githubToken: String {
        didSet { store.set(githubToken, for: Key.githubToken) }
    }

    var track: UpdateTrack {
        didSet { store.set(track, for: Key.track) }
    }

    private(set) var isChecking = false
    private(set) var phase = Phase.idle
    private(set) var progress = 0.0
    var presented: AvailableUpdate?
    var alert: AlertState?
    var installFailure: AlertState?

    private let store = PreferenceStore.shared

    private init() {
        checksAutomatically = store.value(Bool.self, for: Key.checksAutomatically) ?? false
        githubToken = store.value(String.self, for: Key.githubToken) ?? ""
        track = store.value(UpdateTrack.self, for: Key.track) ?? .stable
    }

    func checkOnLaunch() {
        guard Demo.current == nil else { return }
        if store.value(Bool.self, for: Key.checksAutomatically) == nil {
            alert = AlertState(
                title: String(localized: "Check for Updates Automatically?"),
                actions: [
                    AlertState.Action(label: String(localized: "Don’t Check"), role: .cancel) {
                        self.checksAutomatically = false
                    },
                    AlertState.Action(label: String(localized: "Check Automatically"), role: nil, isDefault: true) {
                        self.checksAutomatically = true
                        Task { await self.check(manually: false) }
                    },
                ])
        } else if checksAutomatically {
            Task { await check(manually: false) }
        }
    }

    func check(manually: Bool) async {
        guard !isChecking else { return }
        isChecking = true
        defer { isChecking = false }
        let update: AvailableUpdate?
        do {
            update = try await UpdateChecker.check(track: track, token: githubToken)
        } catch {
            Self.logger.error("check for updates: \(error, privacy: .public)")
            if manually {
                alert = AlertState(String(localized: "Couldn’t Check for Updates"), error: error)
            }
            return
        }
        if let update {
            if manually || store.value(Int.self, for: Key.presentedBuild) != update.build {
                store.set(update.build, for: Key.presentedBuild)
                presented = update
            }
        } else if manually {
            alert = AlertState(title: String(localized: "You’re Up to Date"))
        }
    }

    func install(_ update: AvailableUpdate) async {
        guard phase == .idle else { return }
        phase = .downloading
        progress = 0
        do {
            let package = try await UpdateChecker.download(update, token: githubToken) { progress in
                DispatchQueue.main.async { self.progress = progress }
            }
            phase = .installing
            nonisolated(unsafe) let authorization = try PackageInstaller.authorize()
            try await Task.detached { try PackageInstaller.install(package, authorization: authorization) }.value
        } catch is PackageInstaller.Canceled {
            phase = .idle
            return
        } catch {
            Self.logger.error("install update: \(error, privacy: .public)")
            phase = .idle
            installFailure = AlertState(String(localized: "Couldn’t Install the Update"), error: error)
            return
        }
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        _ = try? await NSWorkspace.shared.openApplication(at: Self.installedApp, configuration: configuration)
        for sheet in NSApp.windows.compactMap(\.attachedSheet) {
            sheet.preventsApplicationTerminationWhenModal = false
        }
        NSApp.terminate(nil)
    }
}
#endif
