#if os(iOS)
import Perception
import SwiftUI
import UIKit

/// Where the input panel puts the trackpad, as the person last arranged it.
struct InputPanelPlacement: Codable, Hashable {
    enum Side: String, Codable {
        case leading
        case trailing
    }

    enum Arrangement: String, Codable {
        case below
        case leading
        case trailing
    }

    /// The trackpad's height under the keyboard, in points.
    var trackpadHeight: Double?
    /// The trackpad's share of the width beside the keyboard.
    var trackpadWidth: Double?
    /// The trackpad's side of the keyboard while the window is wider than tall.
    var trackpadSide = Side.trailing
    /// Where the trackpad goes while the window is taller than wide and wide enough for it beside the keyboard.
    var portraitArrangement = Arrangement.below

    init() {}

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        trackpadHeight = try container.decodeIfPresent(Double.self, forKey: .trackpadHeight)
        trackpadWidth = try container.decodeIfPresent(Double.self, forKey: .trackpadWidth)
        trackpadSide = try container.decodeIfPresent(Side.self, forKey: .trackpadSide) ?? .trailing
        portraitArrangement = try container.decodeIfPresent(Arrangement.self, forKey: .portraitArrangement) ?? .below
    }
}

/// Sets the picture beside the Mac keyboard and trackpad rather than under them, so nothing on the computer is
/// covered while they're up.
struct SessionInputLayout<Screen: View>: View {
    let controller: SessionController
    @ViewBuilder let screen: Screen

    private let settings = AppSettings.shared

    var body: some View {
        WithPerceptionTracking {
            // The reader keeps to the safe area so it can tell the panel where the system's bars are; the layout itself
            // fills the window.
            GeometryReader { proxy in
                let insets = proxy.safeAreaInsets
                let plan = InputPanelPlan(
                    size: CGSize(
                        width: proxy.size.width + insets.leading + insets.trailing,
                        height: proxy.size.height + insets.top + insets.bottom),
                    insets: insets, keyboard: controller.isMacKeyboardVisible, trackpad: controller.isTrackpadVisible,
                    placement: settings.inputPanel, layout: settings.macKeyboardLayout, pictureSize: controller.imageSize)
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        if plan.column == .leading {
                            InputPanelView(controller: controller, plan: plan)
                                .transition(.move(edge: .leading))
                        }
                        screen
                        if plan.column == .trailing {
                            InputPanelView(controller: controller, plan: plan)
                                .transition(.move(edge: .trailing))
                        }
                    }
                    if plan.isAtBottom {
                        InputPanelView(controller: controller, plan: plan)
                            .transition(.move(edge: .bottom))
                    }
                }
                .ignoresSafeArea()
            }
        }
    }
}

/// How the panel is laid out for a window size.
struct InputPanelPlan {
    static let headerHeight: CGFloat = 32
    static let handleHeight: CGFloat = 18
    static let margin: CGFloat = 6
    static let minimumTrackpad: CGFloat = 90

    let size: CGSize
    let insets: EdgeInsets
    let showsKeyboard: Bool
    let showsTrackpad: Bool
    let isLandscape: Bool
    /// The side of the picture the panel sits on, when it holds only the trackpad and the window is wide.
    let column: InputPanelPlacement.Side?
    /// The trackpad's side of the keyboard, or nil when it's under it or alone.
    let trackpadSide: InputPanelPlacement.Side?
    /// Whether the trackpad can go beside the keyboard while the window is taller than wide.
    let fitsTrackpadBeside: Bool
    let isCompact: Bool
    let rowHeight: CGFloat
    let trackpadHeight: CGFloat
    let trackpadWidth: CGFloat
    let usableWidth: CGFloat

    var isAtBottom: Bool { column == nil && (showsKeyboard || showsTrackpad) }
    var keyboardHeight: CGFloat { MacKeyboardView.height(rows: MacKeyboardView.rows, rowHeight: rowHeight) }
    var maximumTrackpadHeight: CGFloat { max(Self.minimumTrackpad, size.height * 0.5) }
    /// Whether the trackpad's height is set by dragging, rather than following the keyboard's beside it.
    var resizesHeight: Bool { showsTrackpad && !isLandscape && trackpadSide == nil }
    var resizesWidth: Bool { showsTrackpad && (column != nil || trackpadSide != nil) }

    init(
        size: CGSize, insets: EdgeInsets, keyboard: Bool, trackpad: Bool, placement: InputPanelPlacement,
        layout: MacKeyboardLayoutID, pictureSize: CGSize
    ) {
        self.size = size
        self.insets = insets
        showsKeyboard = keyboard
        showsTrackpad = trackpad
        isLandscape = size.width > size.height
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        let usable = size.width - insets.leading - insets.trailing - Self.margin * 2
        usableWidth = usable
        fitsTrackpadBeside = !isLandscape && usable >= 600

        column = isLandscape && trackpad && !keyboard ? placement.trackpadSide : nil
        if keyboard, trackpad {
            if isLandscape {
                trackpadSide = placement.trackpadSide
            } else if fitsTrackpadBeside {
                switch placement.portraitArrangement {
                case .below: trackpadSide = nil
                case .leading: trackpadSide = .leading
                case .trailing: trackpadSide = .trailing
                }
            } else {
                trackpadSide = nil
            }
        } else {
            trackpadSide = nil
        }

        let widthShare = min(max(placement.trackpadWidth ?? 0.32, 0.18), 0.5)
        trackpadWidth = column != nil || trackpadSide != nil ? usable * widthShare : usable

        let keyboardWidth = trackpadSide != nil ? usable - trackpadWidth - Self.margin : usable
        isCompact = keyboardWidth < 600
        let units = layout.layout(compact: isCompact).spaceRow.reduce(0) { $0 + $1.width }
        let maximumRow: CGFloat = isPad ? 58 : 50
        // The keyboard keeps to a share of the height, so the picture stays usable.
        let maximumHeight = size.height * (isLandscape ? (isPad ? 0.42 : 0.5) : 0.36)
        let spacing = MacKeyboardView.keySpacing * CGFloat(MacKeyboardView.rows - 1) + MacKeyboardView.padding * 2
        let row = min(
            keyboardWidth / max(units, 1) * 1.25, maximumRow, (maximumHeight - spacing) / CGFloat(MacKeyboardView.rows))
        rowHeight = max(row, 24)

        // Unless the person sized it, the trackpad takes what the picture leaves over at its full width.
        var spare = size.height * 0.2
        if pictureSize.width > 0, pictureSize.height > 0 {
            let picture = size.width * pictureSize.height / pictureSize.width
            let keyboardHeight = keyboard ? MacKeyboardView.height(rows: MacKeyboardView.rows, rowHeight: rowHeight) : 0
            let chrome =
                insets.top + max(insets.bottom, Self.margin) + Self.headerHeight + Self.handleHeight + Self.margin * 2
            spare = max(spare, size.height - picture - keyboardHeight - chrome)
        }
        trackpadHeight = min(
            max(placement.trackpadHeight.map { CGFloat($0) } ?? spare, Self.minimumTrackpad),
            max(Self.minimumTrackpad, size.height * 0.5))
    }
}

private struct InputPanelView: View {
    let controller: SessionController
    let plan: InputPanelPlan

    private let settings = AppSettings.shared
    @State private var resize: CGFloat?

    private var trackpadHeight: CGFloat {
        min(max(plan.trackpadHeight - (resize ?? 0), InputPanelPlan.minimumTrackpad), plan.maximumTrackpadHeight)
    }

    /// Which way a drag to the right moves the trackpad's inner edge.
    private var widthSign: CGFloat {
        (plan.column ?? plan.trackpadSide) == .trailing ? -1 : 1
    }

    private var trackpadWidth: CGFloat {
        let share = (plan.trackpadWidth + widthSign * (resize ?? 0)) / max(plan.usableWidth, 1)
        return plan.usableWidth * min(max(share, 0.18), 0.5)
    }

    var body: some View {
        WithPerceptionTracking {
            VStack(spacing: InputPanelPlan.margin) {
                header
                content
            }
            .padding(.horizontal, InputPanelPlan.margin)
            .padding(.leading, plan.column == .trailing ? 0 : plan.insets.leading)
            .padding(.trailing, plan.column == .leading ? 0 : plan.insets.trailing)
            .padding(.top, plan.column == nil ? 0 : plan.insets.top)
            .padding(.bottom, max(plan.insets.bottom, InputPanelPlan.margin))
            .frame(width: plan.column == nil ? nil : trackpadWidth + InputPanelPlan.margin * 2 + sideInset)
            .background(Color(white: 0.08))
            .environment(\.colorScheme, .dark)
        }
    }

    private var sideInset: CGFloat {
        switch plan.column {
        case .leading: plan.insets.leading
        case .trailing: plan.insets.trailing
        case nil: 0
        }
    }

    @ViewBuilder
    private var content: some View {
        if plan.column != nil {
            trackpad
        } else if let side = plan.trackpadSide {
            HStack(spacing: InputPanelPlan.margin) {
                if side == .leading {
                    trackpad.frame(width: trackpadWidth)
                }
                keyboard
                if side == .trailing {
                    trackpad.frame(width: trackpadWidth)
                }
            }
            .frame(height: plan.keyboardHeight)
        } else {
            if plan.showsKeyboard {
                keyboard
            }
            if plan.showsTrackpad {
                if plan.showsKeyboard {
                    handle
                }
                trackpad.frame(height: trackpadHeight)
            }
        }
    }

    private var keyboard: some View {
        MacKeyboardView(
            controller: controller, layout: settings.macKeyboardLayout, rowHeight: plan.rowHeight, isCompact: plan.isCompact
        )
        .frame(height: plan.keyboardHeight)
    }

    private var trackpad: some View {
        TrackpadView(controller: controller)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(white: 0.17)))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.white.opacity(0.08)))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var header: some View {
        HStack(spacing: 4) {
            headerButton("Keyboard", systemImage: "keyboard", isOn: plan.showsKeyboard) {
                controller.setMacKeyboardVisible(!plan.showsKeyboard)
            }
            if controller.platform != .iOS {
                headerButton("Trackpad", systemImage: "rectangle.and.hand.point.up.left", isOn: plan.showsTrackpad) {
                    controller.setTrackpadVisible(!plan.showsTrackpad)
                }
            }
            if plan.showsKeyboard {
                Button {
                    settings.macKeyboardLayout = settings.macKeyboardLayout.next
                } label: {
                    Text(verbatim: settings.macKeyboardLayout.shortName)
                        .font(.caption.weight(.semibold))
                        .frame(minWidth: 36, minHeight: InputPanelPlan.headerHeight)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(Text("Keyboard Layout"))
                .accessibilityValue(Text(String(localized: settings.macKeyboardLayout.title)))
            }
            Spacer(minLength: 8)
            if plan.resizesWidth {
                grabber(upright: true)
            } else if plan.resizesHeight, !plan.showsKeyboard {
                grabber(upright: false)
            }
            Spacer(minLength: 8)
            if plan.showsTrackpad, plan.isLandscape || plan.fitsTrackpadBeside && plan.showsKeyboard {
                headerButton("Move Trackpad", systemImage: moveSymbol, isOn: false) {
                    withAnimation(.smoothCompat(duration: 0.25)) { moveTrackpad() }
                }
            }
            headerButton("Close", systemImage: "xmark", isOn: false) {
                controller.hideInputPanel()
            }
        }
        .buttonStyle(.plain)
        .labelStyle(.iconOnly)
        .foregroundStyle(Color.white)
        .frame(height: InputPanelPlan.headerHeight)
    }

    private var moveSymbol: String {
        guard !plan.isLandscape else { return "arrow.left.arrow.right" }
        switch settings.inputPanel.portraitArrangement {
        case .below: return "arrow.up.right"
        case .trailing: return "arrow.left"
        case .leading: return "arrow.down"
        }
    }

    /// Moves the trackpad to its next place: from side to side while the window is wide, and from under the keyboard
    /// to its right, to its left and back while it's tall.
    private func moveTrackpad() {
        if plan.isLandscape {
            settings.inputPanel.trackpadSide = settings.inputPanel.trackpadSide == .trailing ? .leading : .trailing
            return
        }
        switch settings.inputPanel.portraitArrangement {
        case .below: settings.inputPanel.portraitArrangement = .trailing
        case .trailing: settings.inputPanel.portraitArrangement = .leading
        case .leading: settings.inputPanel.portraitArrangement = .below
        }
    }

    /// The bar between the keyboard and the trackpad under it, dragged to size the trackpad.
    private var handle: some View {
        Capsule()
            .fill(Color.white.opacity(resize == nil ? 0.35 : 0.6))
            .frame(width: 48, height: 5)
            .frame(maxWidth: .infinity)
            .frame(height: InputPanelPlan.handleHeight)
            .contentShape(Rectangle())
            .gesture(resizeGesture(upright: false))
            .accessibilityHidden(true)
    }

    private func grabber(upright: Bool) -> some View {
        Capsule()
            .fill(Color.white.opacity(resize == nil ? 0.35 : 0.6))
            .frame(width: upright ? 5 : 40, height: upright ? 22 : 5)
            .frame(width: 64, height: InputPanelPlan.headerHeight)
            .contentShape(Rectangle())
            .gesture(resizeGesture(upright: upright))
            .accessibilityHidden(true)
    }

    /// A drag that sizes the trackpad: its width when the bar is upright, its height otherwise.
    private func resizeGesture(upright: Bool) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .global)
            .onChanged { value in
                resize = upright ? value.translation.width : value.translation.height
            }
            .onEnded { _ in
                guard resize != nil else { return }
                if upright {
                    settings.inputPanel.trackpadWidth = Double(trackpadWidth / max(plan.usableWidth, 1))
                } else {
                    settings.inputPanel.trackpadHeight = Double(trackpadHeight)
                }
                resize = nil
            }
    }

    private func headerButton(
        _ title: String.LocalizationValue, systemImage: String, isOn: Bool, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(String(localized: title), systemImage: systemImage)
                .font(.subheadline)
                .frame(width: 38, height: InputPanelPlan.headerHeight)
                .background {
                    if isOn {
                        RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.white.opacity(0.18))
                    }
                }
                .contentShape(Rectangle())
        }
        .accessibilityAddTraits(isOn ? AccessibilityTraits.isSelected : [])
    }
}
#endif
