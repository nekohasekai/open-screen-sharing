import Perception
import SwiftUI

struct ScaleButton: View {
    let controller: SessionController

    var body: some View {
        WithPerceptionTracking {
            if controller.scaleMode == .fit {
                Button { controller.setScale(.actualSize) } label: { Label("Zoom to Actual Size", systemImage: "arrow.up.left.and.arrow.down.right") }
            } else {
                Button { controller.setScale(.fit) } label: { Label("Zoom to Fit", systemImage: "arrow.down.right.and.arrow.up.left") }
            }
        }
    }
}

struct DisplayPicker: View {
    let controller: SessionController
    var inOwnMenu = false

    var body: some View {
        WithPerceptionTracking {
            Picker(selection: Binding(get: { controller.displaySelection }, set: { controller.selectDisplay($0) })) {
                ForEach(controller.displays) { display in
                    Text(display.name).tag(DisplaySelection.display(display.id))
                }
                Text(inOwnMenu ? "All" : "All Displays").tag(DisplaySelection.all)
            } label: {
                Label("Displays", systemImage: SessionCommand.displays.systemImage)
            }
        }
    }
}

extension SessionQuality {
    var title: String.LocalizationValue {
        switch self {
        case .adaptive: "Adaptive Quality"
        case .full: "Full Quality"
        }
    }

    var shortTitle: String.LocalizationValue {
        switch self {
        case .adaptive: "Adaptive"
        case .full: "Full"
        }
    }
}

struct QualityPicker: View {
    let controller: SessionController
    var inOwnMenu = false

    var body: some View {
        WithPerceptionTracking {
            Picker(selection: Binding(get: { controller.quality }, set: { controller.setQuality($0) })) {
                ForEach(SessionQuality.allCases) { quality in
                    Text(String(localized: inOwnMenu ? quality.shortTitle : quality.title)).tag(quality)
                }
            } label: {
                Label("Quality", systemImage: SessionCommand.quality.systemImage)
            }
        }
    }
}

struct ResolutionOptions: View {
    let controller: SessionController

    var body: some View {
        ForEach(VirtualDisplayMode.resolutionScales, id: \.self) { scale in
            WithPerceptionTracking {
                Toggle(
                    resolutionScaleTitle(scale),
                    isOn: Binding(get: { controller.resolutionScale == scale }, set: { _ in controller.setResolutionScale(scale) }))
            }
        }
    }
}

func resolutionScaleTitle(_ scale: Double) -> String {
    scale == 1 ? String(localized: "Native") : String(localized: "\(scale.formatted(.number.precision(.fractionLength(0...2))))×")
}

struct ResolutionPanel: View {
    let controller: SessionController

    @State private var draggedScale: Double?

    private var scale: Double { draggedScale ?? controller.resolutionScale }

    var body: some View {
        WithPerceptionTracking {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Resolution")
                    Spacer()
                    Text(resolutionScaleTitle(scale))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                Slider(
                    value: Binding(
                        get: { scale },
                        set: { value in
                            if draggedScale != nil {
                                draggedScale = value
                            } else {
                                controller.setResolutionScale(value)
                            }
                        }),
                    in: VirtualDisplayMode.resolutionScaleRange,
                    step: VirtualDisplayMode.resolutionScaleStep
                ) {
                    Text("Resolution")
                } onEditingChanged: { editing in
                    if editing {
                        draggedScale = controller.resolutionScale
                    } else if let draggedScale {
                        self.draggedScale = nil
                        controller.setResolutionScale(draggedScale)
                    }
                }
                Text("Raising the resolution by scaling reduces sharpness and increases bandwidth use and picture latency.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .font(.body)
            .padding()
            .frame(width: 340)
        }
    }
}

extension View {
    func resolutionPopover(controller: SessionController, isPresented: Binding<Bool>) -> some View {
        popover(isPresented: isPresented) {
            ResolutionPanel(controller: controller)
                .popoverOnCompact()
        }
        .onDisappear { controller.isShowingResolution = false }
    }
}

struct ClipboardMenuItems: View {
    let controller: SessionController

    var body: some View {
        WithPerceptionTracking {
            Toggle(isOn: Binding(get: { controller.clipboardSharing }, set: { controller.setClipboardSharing($0) })) { Label("Sync Clipboard", systemImage: "arrow.up.arrow.down") }
            Section {
                Button { controller.getClipboardFromMac() } label: { Label("Receive Clipboard", systemImage: "doc.on.clipboard") }
                    .disabled(!controller.capabilities.contains(.clipboardRequest))
                Button { controller.sendClipboardToMac() } label: { Label("Send Clipboard", systemImage: "arrow.up.doc.on.clipboard") }
            }
            .disabled(controller.clipboardSharing)
        }
    }
}

struct QuickInputItems: View {
    let controller: SessionController

    private let inputs = QuickInputs.shared
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    var body: some View {
        WithPerceptionTracking {
            let target = controller.mappingTarget
            Section {
                ForEach(inputs.inputs.filter { !$0.steps(for: target).isEmpty }) { input in
                    Button {
                        controller.perform(input)
                    } label: {
                        Text(verbatim: input.name)
                    }
                }
            }
            Button("Edit…") {
                #if os(macOS)
                HomeRequest.shared.pending = .settings
                openWindow(id: "home")
                #else
                controller.isEditingQuickInputs = true
                #endif
            }
        }
    }
}

#if os(iOS)
struct FunctionKeyItems: View {
    let controller: SessionController

    var body: some View {
        WithPerceptionTracking {
            KeyItems(controller: controller, keys: KeyShortcut.KeyGroup.functionKeys.keys(for: controller.mappingTarget))
        }
    }
}

private struct KeyItems: View {
    let controller: SessionController
    let keys: [KeyShortcut.NamedKey]

    var body: some View {
        ForEach(keys) { key in
            WithPerceptionTracking {
                if let output = key.output {
                    Button {
                        controller.typeKey(output)
                    } label: {
                        Text(verbatim: key.label(for: controller.mappingTarget))
                    }
                }
            }
        }
    }
}
#endif

extension SystemCommand {
    var title: String.LocalizationValue {
        switch self {
        case .missionControl: "Mission Control"
        case .appWindows: "App Windows"
        case .showDesktop: "Desktop"
        case .launchpad: "Apps"
        }
    }

    var systemImage: String {
        switch self {
        case .missionControl: "rectangle.3.group"
        case .appWindows: "rectangle.stack"
        case .showDesktop: "menubar.dock.rectangle"
        case .launchpad: "square.grid.3x3"
        }
    }
}

#if os(iOS)
struct SessionMoreMenuContent: View {
    let controller: SessionController

    var body: some View {
        ForEach(SessionCommandCategory.allCases) { category in
            WithPerceptionTracking {
                let commands = category.commands.filter { $0.isOffered(by: controller) }
                if !commands.isEmpty {
                    Menu {
                        if category == .control {
                            let local: [SessionCommand] = [.observe, .pointerLock, .nativeTouch, .keyboard]
                            Section {
                                items(commands.filter { local.contains($0) })
                            }
                            Section {
                                items(commands.filter { $0 == .quickInput })
                            }
                            Section {
                                items(commands.filter { !local.contains($0) && $0 != .quickInput })
                            }
                        } else {
                            items(commands)
                        }
                    } label: {
                        Label(String(localized: category.title), systemImage: category.systemImage)
                    }
                }
            }
        }
        Section {
            Button { controller.isCustomizingToolbar = true } label: { Label("Customize Toolbar…", systemImage: "slider.horizontal.3") }
        }
    }

    private func items(_ commands: [SessionCommand]) -> some View {
        ForEach(commands) { command in
            SessionCommandView(command: command, controller: controller)
        }
    }
}
#endif
