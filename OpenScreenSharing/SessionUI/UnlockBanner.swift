import LocalAuthentication
import Perception
import SwiftUI

/// Drops down from the toolbar while the Mac shows its lock screen or login window, and types the saved user
/// password into it once the owner authenticates.
struct UnlockBanner: View {
    let controller: SessionController

    @State private var symbol = "lock.open"
    @Environment(\.colorScheme) private var colorScheme

    static let animation = Animation.spring(duration: 0.45, bounce: 0.3)

    var body: some View {
        WithPerceptionTracking {
            Button {
                controller.typeUserPassword()
            } label: {
                HStack(spacing: 10) {
                    ZStack {
                        Image(systemName: symbol)
                            .opacity(controller.isAuthenticatingUserPassword ? 0 : 1)
                        if controller.isAuthenticatingUserPassword {
                            ProgressView()
                                .controlSize(.small)
                        }
                    }
                    .font(.title3)
                    .frame(width: 24, height: 24)
                    Text("Unlock “\(controller.title)”")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                }
                .padding(.leading, 14)
                .padding(.trailing, 18)
                .frame(minHeight: 44)
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(controller.isAuthenticatingUserPassword)
            .glassEffectCompat(tint: colorScheme == .dark ? .black.opacity(0.4) : nil, interactive: true, in: Capsule())
            #if os(iOS)
            .hoverEffect()
            #endif
            .onAppear { symbol = Self.biometrySymbol() }
        }
    }

    private static func biometrySymbol() -> String {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return "lock.open" }
        switch context.biometryType {
        case .faceID: return "faceid"
        case .touchID: return "touchid"
        default:
            if #available(iOS 17, macOS 14, *), context.biometryType == .opticID { return "opticid" }
            return "lock.open"
        }
    }
}

extension SessionController {
    var showsUnlockBanner: Bool {
        remoteSeemsLocked && isControlling && userPasswordPrompt == nil
    }
}
