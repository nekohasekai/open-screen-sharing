import Perception
import SwiftUI
#if os(iOS)
import UIKit
#endif

struct SessionStatusOverlay: View {
    let controller: SessionController
    let onClose: () -> Void

    private var isConnecting: Bool {
        switch controller.phase {
        case .idle, .connecting, .authenticating: true
        case .connected: controller.picture == .none
        default: false
        }
    }

    private var isReconnecting: Bool {
        switch (controller.phase, controller.picture) {
        case (.reconnecting, _): true
        case (.connected, .previous): !controller.hasPicture
        default: false
        }
    }

    private var progressTitle: String? {
        switch (controller.phase, controller.picture) {
        case (.preparingCursors, _): String(localized: "Preparing Remote Cursors…")
        case _ where isConnecting: String(localized: "Connecting…")
        case _ where isReconnecting: String(localized: "Reconnecting…")
        case (.waitingForPermission, _): String(localized: "Waiting for Permission…")
        case (.connected, .previous(let change)): describe(change)
        default: nil
        }
    }

    private func describe(_ change: PictureChange) -> String {
        switch change {
        case .reconnection: String(localized: "Reconnecting…")
        case .resize: String(localized: "Changing Resolution…")
        case .videoStart: String(localized: "Starting High Performance…")
        }
    }

    private var failure: SessionFailure? {
        if case .failed(let failure) = controller.phase { return failure }
        return nil
    }

    private var sessionChoice: SessionChoicePrompt? {
        if case .choosingSession(let prompt) = controller.phase { return prompt }
        return nil
    }

    private var isDisconnected: Bool {
        controller.phase == .disconnected && !controller.isClosing
    }

    var body: some View {
        WithPerceptionTracking {
            ZStack {
                Color.clear
                if let progressTitle {
                    Color.black.opacity(0.55)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                    VStack(spacing: 24) {
                        ProgressView {
                            Text(progressTitle)
                                .foregroundStyle(.white)
                        }
                        #if os(iOS)
                        .controlSize(.large)
                        #endif
                        if controller.phase == .waitingForPermission {
                            Button("Cancel", role: .cancel, action: onClose)
                                .glassButtonStyleCompat()
                                .controlSize(.large)
                        }
                    }
                    .padding()
                    .transition(.opacity)
                }
            }
            .animation(.default, value: progressTitle)
            .onChangeCompat(of: progressTitle) { title in
                if let title {
                    #if os(iOS)
                    UIAccessibility.post(notification: .announcement, argument: title)
                    #else
                    AccessibilityNotification.Announcement(title).post()
                    #endif
                }
            }
            .alert(Binding(get: { failure.map(failureAlert) }, set: { _ in }))
            .alert(
                "Choose Session",
                isPresented: Binding(get: { sessionChoice != nil }, set: { _ in }),
                presenting: sessionChoice
            ) { prompt in
                if prompt.allowsOwnSession {
                    Button("Log In to Your Own Session") {
                        controller.answerSessionChoice(.ownSession)
                    }
                }
                if prompt.allowsShareConsole {
                    Button(sharesOwnDisplay(prompt) ? LocalizedStringKey("Share the Screen") : LocalizedStringKey("Ask to Share Their Screen")) {
                        controller.answerSessionChoice(.shareConsole)
                    }
                }
                Button("Cancel", role: .cancel) {
                    controller.answerSessionChoice(.cancel)
                    onClose()
                }
            } message: { prompt in
                if sharesOwnDisplay(prompt) {
                    Text("Your account is logged in at “\(controller.title)”.")
                } else if !prompt.consoleUser.isEmpty {
                    Text("“\(prompt.consoleUser)” is logged in at “\(controller.title)”.")
                }
            }
            .alert("Connection Closed", isPresented: Binding(get: { isDisconnected }, set: { _ in })) {
                Button("Close", role: .cancel, action: onClose)
            }
        }
    }

    private func sharesOwnDisplay(_ prompt: SessionChoicePrompt) -> Bool {
        guard let name = controller.loginName, !prompt.consoleUser.isEmpty else { return false }
        return prompt.consoleUser.caseInsensitiveCompare(name) == .orderedSame
    }

    private func failureAlert(_ failure: SessionFailure) -> AlertState {
        let remoteProtocol = controller.route.remoteProtocol
        let connectAnyway: () -> Void
        switch failure.kind {
        case .certificateChanged(let fingerprint):
            connectAnyway = { controller.trustCertificate(fingerprint) }
        case .unverifiedServer:
            connectAnyway = { controller.allowUnverifiedLogin() }
        case .takenOver:
            return AlertState(
                failure, computerName: controller.title, remoteProtocol: remoteProtocol, closeLabel: String(localized: "OK"), onClose: onClose)
        default:
            return AlertState(
                failure, computerName: controller.title, remoteProtocol: remoteProtocol, closeLabel: String(localized: "Close"), onClose: onClose)
        }
        let presentation = failure.presentation(computerName: controller.title, remoteProtocol: remoteProtocol)
        return AlertState(
            title: presentation.title,
            message: presentation.reason ?? failure.message,
            actions: [
                AlertState.Action(label: String(localized: "Connect Anyway"), role: nil, perform: connectAnyway),
                AlertState.Action(label: String(localized: "Close"), role: .cancel, isDefault: true, perform: onClose),
            ])
    }
}

extension SessionFailure {
    func presentation(computerName name: String, remoteProtocol: RemoteProtocol) -> (title: String, reason: String?) {
        let failed = String(localized: "Connection Failed")
        let closed = String(localized: "Connection Closed")
        let rdp = remoteProtocol == .rdp
        let known = { (reason: String) in message.isEmpty ? reason : nil }
        return switch kind {
        case .hostNotFound:
            (failed, known(String(localized: "Couldn’t find “\(name)”.")))
        case .permissionDenied:
            (
                failed,
                known(
                    rdp
                        ? String(localized: "This account isn’t allowed to sign in to “\(name)” remotely.")
                        : String(localized: "“\(name)” declined the request to share its screen."))
            )
        case .authenticationFailed:
            (failed, known(String(localized: "The user name or password is incorrect.")))
        case .ownScreen:
            (failed, known(String(localized: "“\(name)” is the Mac you’re using.")))
        case .unsupported:
            (failed, known(String(localized: "“\(name)” offers no sign-in method this app supports.")))
        case .unreachable, .other:
            (failed, nil)
        case .closedByServer:
            (closed, known(rdp ? String(localized: "“\(name)” ended the session.") : String(localized: "“\(name)” ended screen sharing.")))
        case .takenOver:
            (
                closed,
                known(
                    rdp
                        ? String(localized: "Another client took over this connection.")
                        : String(localized: "Another Open Screen Sharing client took over this connection."))
            )
        case .connectionLost, .ended, .drawingFailed:
            (closed, nil)
        case .certificateChanged:
            (
                String(localized: "Can’t Verify Identity"),
                rdp
                    ? String(
                        localized:
                            "“\(name)” presented a different certificate than last time. If the computer wasn’t reinstalled and didn’t renew its certificate, another device may be pretending to be it."
                    )
                    : String(localized: "“\(name)” has a different key than last time. If the Mac wasn’t reinstalled, another device may be pretending to be it.")
            )
        case .unverifiedServer:
            (
                String(localized: "Can’t Verify Identity"),
                String(
                    localized:
                        "“\(name)” only supports an older sign-in method. If another device is pretending to be it, that device could see your password."
                )
            )
        case .highPerformance(.busy, _):
            (
                String(localized: "Screen Sharing Busy"),
                known(
                    String(
                        localized:
                            "Another device is using Apple VNC. Apple VNC has no built-in way to take over, but if the other device is also running Open Screen Sharing, it can be taken over automatically through an extension protocol."
                    ))
            )
        case .highPerformance(.unsupported, started: false):
            (failed, known(String(localized: "“\(name)” doesn’t support High Performance.")))
        case .highPerformance(_, let started):
            (started ? closed : failed, nil)
        }
    }
}

extension AlertState {
    init(
        _ failure: SessionFailure, computerName: String, remoteProtocol: RemoteProtocol, closeLabel: String = String(localized: "OK"),
        onClose: @escaping () -> Void = {}
    ) {
        let presentation = failure.presentation(computerName: computerName, remoteProtocol: remoteProtocol)
        guard let reason = presentation.reason else {
            self.init(presentation.title, errorMessage: failure.message, closeLabel: closeLabel, onClose: onClose)
            return
        }
        self.init(title: presentation.title, message: reason, actions: [Action(label: closeLabel, role: .cancel, isDefault: true, perform: onClose)])
    }
}
