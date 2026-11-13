import Perception
import SwiftUI

nonisolated private struct SessionControllerKey: FocusedValueKey {
    typealias Value = SessionController
}

nonisolated private struct HomeActionsKey: FocusedValueKey {
    typealias Value = HomeActions
}

nonisolated extension FocusedValues {
    var sessionController: SessionController? {
        get { self[SessionControllerKey.self] }
        set { self[SessionControllerKey.self] = newValue }
    }

    var homeActions: HomeActions? {
        get { self[HomeActionsKey.self] }
        set { self[HomeActionsKey.self] = newValue }
    }
}

struct AppCommands: Commands {
    @FocusedValue(\.sessionController) private var session
    @FocusedValue(\.homeActions) private var home
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    private var homeEnabled: Bool { home != nil && session == nil }

    #if os(macOS)
    private func openHome(_ request: HomeRequest.Pending) {
        HomeRequest.shared.pending = request
        openWindow(id: "home")
    }
    #endif

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            #if os(iOS)
            Button("New Connection…") { home?.newConnection() }
                .modifier(ActionShortcut(action: .newConnection))
                .disabled(!homeEnabled)
            #else
            Button("New Connection…") { openHome(.newConnection) }
                .modifier(ActionShortcut(action: .newConnection))
            #endif
        }

        #if os(macOS)
        CommandGroup(after: .appInfo) {
            CheckForUpdatesButton()
        }

        CommandGroup(replacing: .appSettings) {
            Button("Settings…") { openHome(.settings) }
                .keyboardShortcut(",")
        }
        #endif

        CommandGroup(after: .newItem) {
            Section {
                Button("Disconnect") { session?.close() }
                    .modifier(ActionShortcut(action: .disconnect))
                    .disabled(session == nil)
            }
        }

        CommandGroup(after: .pasteboard) {
            ClipboardCommandItems(session: session)
        }

        CommandGroup(before: .toolbar) {
            #if os(macOS)
            if homeEnabled {
                ComputerLayoutToggles()
            }
            #endif
            ViewCommandItems(session: session)
        }

        CommandMenu("Sound") {
            SoundCommandItems(session: session)
        }

        CommandMenu("Control") {
            ControlCommandItems(session: session)
        }

        CommandMenu("Debug") {
            DebugInfoToggle()
        }

        #if os(macOS)
        CommandGroup(replacing: .help) {}
        #endif
    }
}

private struct ClipboardCommandItems: View {
    let session: SessionController?

    var body: some View {
        WithPerceptionTracking {
            Section {
                Toggle(isOn: Binding(get: { session?.clipboardSharing ?? false }, set: { session?.setClipboardSharing($0) })) { Label("Sync Clipboard", systemImage: "arrow.up.arrow.down") }
                    .modifier(ActionShortcut(action: .syncClipboard))
                    .disabled(session == nil)
                Button { session?.getClipboardFromMac() } label: { Label("Receive Clipboard", systemImage: "doc.on.clipboard") }
                    .modifier(ActionShortcut(action: .receiveClipboard))
                    .disabled(session?.phase != .connected || session?.clipboardSharing == true)
                Button { session?.sendClipboardToMac() } label: { Label("Send Clipboard", systemImage: "arrow.up.doc.on.clipboard") }
                    .modifier(ActionShortcut(action: .sendClipboard))
                    .disabled(session?.phase != .connected || session?.clipboardSharing == true)
            }
        }
    }
}

private struct ViewCommandItems: View {
    let session: SessionController?

    var body: some View {
        WithPerceptionTracking {
            Section {
                Toggle("Zoom Mode", isOn: Binding(get: { session?.isZoomModeOn ?? false }, set: { session?.setZoomMode($0) }))
                    .modifier(ActionShortcut(action: .zoomMode))
                    .disabled(session == nil)
                if session?.pictureMatchesWindow != true {
                    Button(session?.scaleMode == .fit ? "Zoom to Actual Size" : "Zoom to Fit") {
                        session?.setScale(session?.scaleMode == .fit ? .actualSize : .fit)
                    }
                    .modifier(ActionShortcut(action: .actualSize))
                    .disabled(session == nil)
                }
            }
            if let session, session.showsDisplayPicker {
                Section {
                    ForEach(Array(session.displays.enumerated()), id: \.element.id) { index, display in
                        WithPerceptionTracking {
                            Toggle(
                                display.name,
                                isOn: Binding(
                                    get: { session.displaySelection == .display(display.id) },
                                    set: { _ in session.selectDisplay(.display(display.id)) }
                                )
                            )
                            .modifier(ActionShortcut(action: ShortcutAction.display(at: index)))
                        }
                    }
                    Toggle("All Displays", isOn: Binding(get: { session.displaySelection == .all }, set: { _ in session.selectDisplay(.all) }))
                        .modifier(ActionShortcut(action: .allDisplays))
                }
                .disabled(session.phase != .connected)
            }
            if session.map({ SessionCommand.quality.isOffered(by: $0) }) ?? true {
                Section {
                    ForEach(SessionQuality.allCases) { quality in
                        WithPerceptionTracking {
                            Toggle(isOn: Binding(get: { session?.quality == quality }, set: { _ in session?.setQuality(quality) })) {
                                Text(String(localized: quality.title))
                            }
                            .modifier(ActionShortcut(action: ShortcutAction.quality(quality)))
                        }
                    }
                    .disabled(session == nil)
                }
            }
            Section {
                Menu("Resolution") {
                    if let session {
                        ResolutionOptions(controller: session)
                    }
                }
                .disabled(!(session?.offersResolution ?? false) || !(session?.isControlling ?? false))
                Toggle("High Dynamic Range", isOn: Binding(get: { session?.hdrEnabled ?? false }, set: { session?.setHDR($0) }))
                    .modifier(ActionShortcut(action: .hdr))
                    .disabled(!(session?.canUseHDR ?? false))
            }
            Section {
                #if os(iOS)
                Button(session?.isToolbarVisible == true ? "Hide Toolbar" : "Show Toolbar") { session?.toggleToolbar() }
                    .modifier(ActionShortcut(action: .toolbar))
                    .disabled(session == nil || session?.isToolbarDocked == true)
                #endif
                Button("Customize Toolbar…") { session?.isCustomizingToolbar = true }
                    .modifier(ActionShortcut(action: .customizeToolbar))
                    .disabled(session == nil)
            }
        }
    }
}

private struct SoundCommandItems: View {
    let session: SessionController?

    var body: some View {
        WithPerceptionTracking {
            Toggle("Receive Audio", isOn: Binding(get: { session?.receivesAudio ?? false }, set: { session?.setReceivesAudio($0) }))
                .modifier(ActionShortcut(action: .receiveAudio))
                .disabled(session.map { !SessionCommand.audio.isOffered(by: $0) } ?? true)
            Toggle("Send Audio", isOn: Binding(get: { session?.sendsAudio ?? false }, set: { session?.setSendsAudio($0) }))
                .modifier(ActionShortcut(action: .sendAudio))
                .disabled(session.map { !SessionCommand.sendAudio.isOffered(by: $0) } ?? true)
        }
    }
}

private struct ControlCommandItems: View {
    let session: SessionController?

    private var controlling: Bool { session?.isControlling ?? false }

    var body: some View {
        WithPerceptionTracking {
            Section {
                #if os(iOS)
                if let session, SessionCommand.nativeTouch.isOffered(by: session) {
                    Toggle("Native Touch", isOn: Binding(get: { session.isNativeTouchOn }, set: { session.setNativeTouch($0) }))
                        .disabled(!controlling)
                }
                #endif
                Toggle("Observe Mode", isOn: Binding(get: { session?.mode == .observe }, set: { session?.setMode($0 ? .observe : .control) }))
                    .modifier(ActionShortcut(action: .observe))
                    .disabled(session.map { !SessionCommand.observe.isOffered(by: $0) || !SessionCommand.observe.isEnabled(in: $0) } ?? true)
                Toggle("Pointer Lock", isOn: Binding(get: { session?.isPointerLockOn ?? false }, set: { session?.setPointerLock($0) }))
                    .modifier(ActionShortcut(action: .pointerLock))
                    .disabled(session.map { !SessionCommand.pointerLock.isOffered(by: $0) || !$0.canLockPointer } ?? true)
                #if os(iOS)
                Button(session?.isKeyboardVisible == true ? "Hide Keyboard" : "Show Keyboard") { session?.toggleKeyboard() }
                    .modifier(ActionShortcut(action: .keyboard))
                    .disabled(!controlling)
                #endif
            }
            Section {
                Menu("Quick Input") {
                    if let session {
                        QuickInputItems(controller: session)
                    }
                }
                .disabled(!controlling)
            }
            if session?.info.platform != .pc {
                Section {
                    ForEach(SystemCommand.allCases) { command in
                        WithPerceptionTracking {
                            Button(String(localized: command.title)) { session?.perform(command) }
                                .modifier(ActionShortcut(action: ShortcutAction.system(command)))
                                .disabled(!controlling || !(session?.canPerform(command) ?? false))
                        }
                    }
                }
            }
        }
    }
}

#if os(macOS)
private struct CheckForUpdatesButton: View {
    private let updates = UpdateManager.shared
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("Check for Updates…") {
            openWindow(id: "home")
            Task { await updates.check(manually: true) }
        }
        .disabled(updates.isChecking)
    }
}

private struct ComputerLayoutToggles: View {
    private let settings = AppSettings.shared

    var body: some View {
        Section {
            Toggle("as Icons", isOn: Binding(get: { settings.computerLayout == .grid }, set: { _ in settings.computerLayout = .grid }))
                .modifier(ActionShortcut(action: .viewAsIcons))
            Toggle("as List", isOn: Binding(get: { settings.computerLayout == .list }, set: { _ in settings.computerLayout = .list }))
                .modifier(ActionShortcut(action: .viewAsList))
        }
    }
}
#endif

private struct DebugInfoToggle: View {
    private let settings = AppSettings.shared

    var body: some View {
        WithPerceptionTracking {
            Toggle("Debug Panel", isOn: Binding(get: { settings.showsDebugInfo }, set: { settings.showsDebugInfo = $0 }))
                .modifier(ActionShortcut(action: .debugPanel))
        }
    }
}
