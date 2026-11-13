import Perception
import SwiftUI

enum SessionCommand: String, CaseIterable, Identifiable {
    case zoomMode, scale, displays, quality, resolution, hdr
    case audio, sendAudio
    case nativeTouch
    case observe = "control"
    case pointerLock, keyboard, quickInput
    case missionControl, appWindows, showDesktop, launchpad
    case clipboard
    case debugInfo

    var id: Self { self }

    var title: String.LocalizationValue {
        switch self {
        case .zoomMode: "Zoom Mode"
        case .scale: "Scaling"
        case .displays: "Displays"
        case .quality: "Quality"
        case .resolution: "Resolution"
        case .hdr: "High Dynamic Range"
        case .audio: "Receive Audio"
        case .sendAudio: "Send Audio"
        case .observe: "Observe Mode"
        case .pointerLock: "Pointer Lock"
        case .nativeTouch: "Native Touch"
        case .keyboard: "Keyboard"
        case .quickInput: "Quick Input"
        case .missionControl, .appWindows, .showDesktop, .launchpad: systemCommand!.title
        case .clipboard: "Clipboard"
        case .debugInfo: "Debug Panel"
        }
    }

    var systemImage: String {
        switch self {
        case .zoomMode: "plus.magnifyingglass"
        case .scale: "arrow.up.left.and.arrow.down.right"
        case .displays: "display.2"
        case .quality: "sparkles.tv"
        case .resolution: "aspectratio"
        case .hdr: "sun.max"
        case .audio: "speaker.wave.2"
        case .sendAudio: "mic"
        case .observe: "eye"
        case .pointerLock: "computermouse"
        case .nativeTouch: "hand.tap"
        case .keyboard: "keyboard"
        case .quickInput: "text.insert"
        case .missionControl, .appWindows, .showDesktop, .launchpad: systemCommand!.systemImage
        case .clipboard: if #available(iOS 16, *) { "list.clipboard" } else { "doc.on.clipboard" }
        case .debugInfo: if #available(iOS 17, *) { "gauge.with.dots.needle.67percent" } else { "gauge" }
        }
    }

    var category: SessionCommandCategory {
        switch self {
        case .zoomMode, .scale, .displays, .quality, .resolution, .hdr: .view
        case .audio, .sendAudio: .sound
        case .observe, .pointerLock, .nativeTouch, .keyboard, .quickInput, .missionControl, .appWindows, .showDesktop, .launchpad: .control
        case .clipboard: .clipboard
        case .debugInfo: .debug
        }
    }

    var systemCommand: SystemCommand? {
        switch self {
        case .missionControl: .missionControl
        case .appWindows: .appWindows
        case .showDesktop: .showDesktop
        case .launchpad: .launchpad
        default: nil
        }
    }

    func applies(to platform: RemotePlatform) -> Bool {
        switch self {
        case .missionControl, .appWindows, .showDesktop, .launchpad: platform == .mac
        case .zoomMode, .scale, .displays, .quality, .resolution, .hdr, .audio, .sendAudio, .observe, .pointerLock, .quickInput, .clipboard,
            .debugInfo:
            true
        #if os(iOS)
        case .keyboard, .nativeTouch: true
        #else
        case .keyboard, .nativeTouch: false
        #endif
        }
    }

    func isOffered(by controller: SessionController) -> Bool {
        guard applies(to: controller.info.platform) else { return false }
        let capabilities = controller.capabilities
        let known = !capabilities.isEmpty
        func offers(_ capability: SessionCapabilities) -> Bool { !known || capabilities.contains(capability) }
        switch self {
        case .zoomMode, .debugInfo: return true
        case .scale: return !controller.pictureMatchesWindow
        case .quality: return !controller.usesHighPerformance && offers(.quality)
        case .audio: return controller.usesHighPerformance || capabilities.contains(.audio)
        case .sendAudio: return controller.route.remoteProtocol == .rdp
        case .displays: return controller.showsDisplayPicker
        case .resolution: return controller.offersResolution
        case .hdr:
            return controller.usesHighPerformance && controller.displaySupportsHDR && offers(.highPerformance)
                && controller.networkSupportsHDR
        case .observe: return offers(.control) && offers(.observe)
        case .keyboard, .quickInput: return offers(.control)
        case .nativeTouch: return capabilities.contains(.touch)
        #if os(iOS)
        case .pointerLock: return offers(.control) && PointerLock.hasMouse
        #else
        case .pointerLock: return offers(.control)
        #endif
        case .missionControl, .appWindows, .showDesktop, .launchpad:
            return !known || controller.canPerform(systemCommand!)
        case .clipboard: return offers(.clipboard)
        }
    }

    func isEnabled(in controller: SessionController) -> Bool {
        let connected = controller.phase == .connected
        switch self {
        case .zoomMode, .scale, .quality, .audio, .sendAudio, .debugInfo: return true
        case .displays, .clipboard: return connected
        case .resolution: return controller.isControlling
        case .hdr: return controller.canUseHDR
        case .observe: return controller.capabilities.contains(.control)
        case .pointerLock: return controller.canLockPointer
        case .nativeTouch, .keyboard, .quickInput, .missionControl, .appWindows, .showDesktop, .launchpad:
            return controller.isControlling
        }
    }
}

enum SessionCommandCategory: CaseIterable, Identifiable {
    case view, sound, control, clipboard, debug

    var id: Self { self }

    var title: String.LocalizationValue {
        switch self {
        case .view: "View"
        case .sound: "Sound"
        case .control: "Control"
        case .clipboard: "Clipboard"
        case .debug: "Debug"
        }
    }

    var systemImage: String {
        switch self {
        case .view: "display"
        case .sound: "speaker.wave.2"
        case .control: "cursorarrow.rays"
        case .clipboard: if #available(iOS 16, *) { "list.clipboard" } else { "doc.on.clipboard" }
        case .debug: "ladybug"
        }
    }

    var commands: [SessionCommand] {
        SessionCommand.allCases.filter { $0.category == self }
    }
}

extension SessionController {
    var showsDisplayPicker: Bool {
        displays.count > 1 && !usesHighPerformance
    }

    var offersResolution: Bool {
        wantsVirtualDisplay && (capabilities.isEmpty || capabilities.contains(.virtualDisplay))
    }
}

enum SessionCommandStyle {
    case barItem
    case windowToolbarItem
    case menuItem

    var isBar: Bool { self != .menuItem }
}

struct SessionCommandView: View {
    let command: SessionCommand
    let controller: SessionController
    var style: SessionCommandStyle = .menuItem

    private let settings = AppSettings.shared

    var body: some View {
        WithPerceptionTracking {
            content
                .disabled(!command.isEnabled(in: controller))
        }
    }

    @ViewBuilder
    private var content: some View {
        switch command {
        case .zoomMode:
            toggle(isOn: Binding(get: { controller.isZoomModeOn }, set: { controller.setZoomMode($0) }))
        case .scale:
            ScaleButton(controller: controller)
        case .displays:
            items { DisplayPicker(controller: controller, inOwnMenu: style.isBar).pickerStyle(.inline) }
        case .quality:
            items { QualityPicker(controller: controller, inOwnMenu: style.isBar).pickerStyle(.inline) }
        case .resolution:
            switch style {
            case .barItem, .windowToolbarItem:
                Button { controller.isShowingResolution = true } label: { label }
                    .resolutionPopover(
                        controller: controller,
                        isPresented: Binding(get: { controller.isShowingResolution }, set: { controller.isShowingResolution = $0 }))
            case .menuItem:
                #if os(iOS)
                Button { controller.isShowingResolution = true } label: { Label("Resolution…", systemImage: command.systemImage) }
                #else
                Menu { ResolutionOptions(controller: controller) } label: { label }
                #endif
            }
        case .hdr:
            toggle(isOn: Binding(get: { controller.hdrEnabled && controller.canUseHDR }, set: { controller.setHDR($0) }))
        case .audio:
            toggle(isOn: Binding(get: { controller.receivesAudio }, set: { controller.setReceivesAudio($0) }))
        case .sendAudio:
            toggle(isOn: Binding(get: { controller.sendsAudio }, set: { controller.setSendsAudio($0) }))
        case .observe:
            toggle(isOn: Binding(get: { controller.mode == .observe }, set: { controller.setMode($0 ? .observe : .control) }))
        case .pointerLock:
            toggle(isOn: Binding(get: { controller.isPointerLockOn }, set: { controller.setPointerLock($0) }))
        case .nativeTouch:
            toggle(isOn: Binding(get: { controller.isNativeTouchOn }, set: { controller.setNativeTouch($0) }))
        case .keyboard:
            Button {
                controller.toggleKeyboard()
                if style == .barItem, controller.isToolbarVisible {
                    controller.toggleToolbar()
                }
            } label: {
                Label(
                    controller.isKeyboardVisible ? "Hide Keyboard" : "Show Keyboard",
                    systemImage: controller.isKeyboardVisible ? "keyboard.chevron.compact.down" : "keyboard"
                )
            }
        case .missionControl, .appWindows, .showDesktop, .launchpad:
            let systemCommand = command.systemCommand!
            Button {
                controller.perform(systemCommand)
            } label: { label }
        case .quickInput:
            Menu { QuickInputItems(controller: controller) } label: { label }
        case .clipboard:
            items(isOn: controller.clipboardSharing) { ClipboardMenuItems(controller: controller) }
        case .debugInfo:
            toggle(isOn: Binding(get: { settings.showsDebugInfo }, set: { settings.showsDebugInfo = $0 }))
        }
    }

    private var label: some View {
        Label(String(localized: command.title), systemImage: command.systemImage)
    }

    @ViewBuilder
    private func toggle(isOn: Binding<Bool>) -> some View {
        switch style {
        case .barItem: Toggle(isOn: isOn) { label }.toggleStyle(BarToggleStyle())
        case .windowToolbarItem: Toggle(isOn: isOn) { label }.toggleStyle(.button)
        case .menuItem: Toggle(isOn: isOn) { label }
        }
    }

    @ViewBuilder
    private func items(isOn: Bool = false, @ViewBuilder _ items: () -> some View) -> some View {
        if style.isBar {
            Menu {
                items()
            } label: {
                label
            }
            .barItemSelected(style == .barItem && isOn)
        } else {
            items()
        }
    }
}

struct BarToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            configuration.label
        }
        .barItemSelected(configuration.isOn)
    }
}

extension View {
    func barItemSelected(_ isSelected: Bool) -> some View {
        background {
            if isSelected {
                Circle().fill(Color.accentColor).padding(3)
            }
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
