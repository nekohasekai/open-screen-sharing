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
                Text("“\(controller.title)” Is in Use"),
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
                    Text("Your account is logged in at the Mac.")
                } else if !prompt.consoleUser.isEmpty {
                    Text("“\(prompt.consoleUser)” is using it right now.")
                }
            }
            .alert("Disconnected", isPresented: Binding(get: { isDisconnected }, set: { _ in })) {
                Button("Close", role: .cancel, action: onClose)
            }
        }
    }

    private func sharesOwnDisplay(_ prompt: SessionChoicePrompt) -> Bool {
        guard let name = controller.loginName, !prompt.consoleUser.isEmpty else { return false }
        return prompt.consoleUser.caseInsensitiveCompare(name) == .orderedSame
    }

    private func failureAlert(_ failure: SessionFailure) -> AlertState {
        let title = failure.title(computerName: controller.title, remoteProtocol: controller.route.remoteProtocol)
        let message: String
        let connectAnyway: () -> Void
        switch failure.kind {
        case .certificateChanged(let fingerprint) where controller.route.remoteProtocol == .rdp:
            message = String(
                localized:
                    "This is expected after the computer is reinstalled or renews its certificate. If neither happened, another device may be pretending to be this computer."
            )
            connectAnyway = { controller.trustCertificate(fingerprint) }
        case .certificateChanged(let fingerprint):
            message = String(localized: "This is expected after the Mac is reinstalled. If it wasn’t, another device may be pretending to be this Mac.")
            connectAnyway = { controller.trustCertificate(fingerprint) }
        case .unverifiedServer:
            message = String(
                localized:
                    "This computer only offers an older sign-in method that can’t confirm its identity. If another device is pretending to be this computer, it could see your password."
            )
            connectAnyway = { controller.allowUnverifiedLogin() }
        default:
            return AlertState(title, errorMessage: failure.message, closeLabel: String(localized: "Close"), onClose: onClose)
        }
        return AlertState(
            title: title,
            message: message,
            actions: [
                AlertState.Action(label: String(localized: "Connect Anyway"), role: nil, perform: connectAnyway),
                AlertState.Action(label: String(localized: "Close"), role: .cancel, isDefault: true, perform: onClose),
            ])
    }
}

extension SessionFailure {
    func title(computerName name: String, remoteProtocol: RemoteProtocol) -> String {
        switch kind {
        case .authenticationFailed: String(localized: "Couldn’t Sign In to “\(name)”")
        case .permissionDenied: String(localized: "“\(name)” Declined the Request")
        case .hostNotFound: String(localized: "Couldn’t Find “\(name)”")
        case .ownScreen: String(localized: "You Can’t Control Your Own Screen")
        case .unreachable, .other: String(localized: "Couldn’t Connect to “\(name)”")
        case .connectionLost: String(localized: "Lost the Connection to “\(name)”")
        case .ended, .drawingFailed: String(localized: "Disconnected from “\(name)”")
        case .closedByServer:
            remoteProtocol == .rdp
                ? String(localized: "“\(name)” Ended the Session") : String(localized: "“\(name)” Ended Screen Sharing")
        case .unsupported: String(localized: "“\(name)” Isn’t Supported")
        case .certificateChanged:
            remoteProtocol == .rdp
                ? String(localized: "“\(name)” Has a New Certificate") : String(localized: "Couldn’t Verify “\(name)”")
        case .unverifiedServer: String(localized: "Couldn’t Verify “\(name)”")
        case .highPerformance(.busy): String(localized: "Another Device Is Using High Performance on “\(name)”")
        case .highPerformance(.unsupported): String(localized: "“\(name)” Doesn’t Support High Performance")
        case .highPerformance(.undecodable): String(localized: "This Device Can’t Play High Performance Video")
        case .highPerformance(.refused): String(localized: "“\(name)” Refused High Performance")
        case .highPerformance: String(localized: "High Performance Stopped")
        }
    }
}
