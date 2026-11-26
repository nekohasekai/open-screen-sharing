import Perception
import SwiftUI

struct ToolbarDock: Equatable {
    enum Edge {
        case left
        case top
    }

    let edge: Edge
    let area: CGRect

    init?(letterbox: Letterbox?, itemWidth: CGFloat) {
        guard let letterbox else { return nil }
        let thickness = itemWidth + 2 * ToolbarLayout.dockClearance
        if letterbox.left.width >= thickness {
            edge = .left
            area = letterbox.left
        } else if letterbox.top.height >= thickness {
            edge = .top
            area = letterbox.top
        } else if let top = letterbox.topBetweenCorners, top.height >= thickness {
            edge = .top
            area = top
        } else {
            return nil
        }
    }

    var length: CGFloat {
        edge == .left ? area.height : area.width
    }
}

struct FloatingToolbar: View {
    #if os(iOS)
    private static let coordinateSpaceName = "floatingToolbar"

    private struct IdleKey: Equatable {
        var visible: Bool
        var dragging: Bool
        var hovered: Bool
        var stashed: Bool
        var anchor: FloatingToolbarAnchor
    }

    private struct EdgeTouches: Equatable {
        var left: Bool
        var right: Bool
        var top: Bool
        var bottom: Bool

        func contains(_ edge: FloatingToolbarEdge) -> Bool {
            switch edge {
            case .left: left
            case .right: right
            case .top: top
            case .bottom: bottom
            }
        }
    }
    #endif

    let controller: SessionController
    let plan: ToolbarPlan
    let dock: ToolbarDock?
    let closes: Bool
    let onClose: () -> Void

    @ScaledMetric(relativeTo: .body) private var itemSize = ToolbarLayout.itemSize
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.layoutDirection) private var layoutDirection
    #if os(iOS)
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @GestureState private var isDragging = false
    @State private var dragStart: CGPoint?
    @State private var dragPoint: CGPoint?
    @State private var windowFrame = CGRect.zero
    private let settings = AppSettings.shared
    #endif

    private var glassTint: Color? {
        colorScheme == .dark ? .black.opacity(0.4) : nil
    }

    var body: some View {
        #if os(iOS)
        WithPerceptionTracking {
            if let dock {
                docked(in: dock)
            } else {
                floating
            }
        }
        #else
        if let dock {
            docked(in: dock)
        }
        #endif
    }

    private func docked(in dock: ToolbarDock) -> some View {
        let left = dock.edge == .left
        let margin = min(ToolbarLayout.dockMargin, ((left ? dock.area.width : dock.area.height) - itemSize) / 2)
        return SessionCommandBar(controller: controller, plan: plan, axis: left ? .vertical : .horizontal, onClose: closes ? onClose : nil)
            .environment(\.layoutDirection, layoutDirection)
            .padding(left ? .trailing : .bottom, margin)
            .frame(width: dock.area.width, height: dock.area.height, alignment: left ? .trailing : .bottom)
            .position(x: dock.area.midX, y: dock.area.midY)
            .environment(\.layoutDirection, .leftToRight)
            #if os(iOS)
            .ignoresSafeArea()
            #endif
    }

    #if os(iOS)
    private var floating: some View {
        let isFullScreen = controller.isFullScreen
        return GeometryReader { proxy in
            let frame = proxy.frame(in: .global)
            let touchesEdges = EdgeTouches(
                left: windowFrame.width > 0 && frame.minX - windowFrame.minX < 1,
                right: windowFrame.width > 0 && windowFrame.maxX - frame.maxX < 1,
                top: windowFrame.height > 0 && frame.minY - windowFrame.minY < 1,
                bottom: windowFrame.height > 0 && windowFrame.maxY - frame.maxY < 1
            )
            WithPerceptionTracking {
                floatingContent(in: proxy.size, touchesEdges: touchesEdges)
            }
        }
        .coordinateSpace(name: Self.coordinateSpaceName)
        .ignoresSafeArea(isFullScreen ? .container : [])
        .background {
            Color.clear
                .onGeometryChangeCompat(for: CGRect.self) { $0.frame(in: .global) } action: { windowFrame = $0 }
                .ignoresSafeArea()
        }
        .environment(\.layoutDirection, .leftToRight)
        .task(
            id: IdleKey(
                visible: controller.isToolbarVisible,
                dragging: dragPoint != nil,
                hovered: controller.isPointerNearToolbarHandle,
                stashed: controller.isToolbarStashed,
                anchor: settings.floatingToolbarAnchor
            )
        ) {
            if controller.isToolbarVisible || dragPoint != nil || controller.isPointerNearToolbarHandle {
                if controller.isToolbarStashed {
                    withAnimation(.smoothCompat(duration: 0.25)) {
                        controller.isToolbarStashed = false
                    }
                }
                return
            }
            guard !controller.isToolbarStashed else { return }
            do {
                try await Task.sleep(seconds: 2.2)
            } catch { return }
            withAnimation(.smoothCompat(duration: 0.25)) {
                controller.isToolbarStashed = true
            }
        }
        .onDisappear {
            controller.toolbarHandleFrame = nil
            controller.isToolbarStashed = false
        }
    }

    private func floatingContent(in size: CGSize, touchesEdges: EdgeTouches) -> some View {
        let anchor = settings.floatingToolbarAnchor
        let obstructions = controller.isFullScreen ? controller.screenObstructions : ScreenObstructions()
        let placement = FloatingToolbarPlacement(
            size: size,
            anchor: anchor,
            itemWidth: itemSize,
            closes: closes,
            obstructions: obstructions
        )
        let floatingPlan = ToolbarLayout.layout(for: controller).plan(for: controller, slots: placement.slots)
        let center = dragPoint ?? placement.ballCenter
        let half = itemSize / 2
        let gap = ToolbarLayout.capsuleGroupSpacing
        let margin = ToolbarLayout.floatingMargin
        let barLeading = FloatingToolbarAnchor.clamp(center.x, margin + half, size.width - margin - half) - half
        let barTop = FloatingToolbarAnchor.clamp(center.y, margin + half, size.height - margin - half) - half

        let scale = itemSize / ToolbarLayout.itemSize
        let stashVisibleThickness = ToolbarLayout.stashVisibleThickness * scale
        let stashCornerRadius = ToolbarLayout.stashCornerRadius * scale
        let stashOverhang = stashCornerRadius
        let stashHitThickness = ToolbarLayout.stashHitThickness * scale
        let stashedAlong =
            touchesEdges.contains(anchor.edge)
            ? anchor.stashedAlong(
                from: placement.ballCenter,
                in: size,
                length: itemSize,
                thickness: stashVisibleThickness,
                obstructions: obstructions
            )
            : nil
        let stashed = controller.isToolbarStashed && !controller.isToolbarVisible && dragPoint == nil
        let stashedAsTab = stashed && stashedAlong != nil
        let dimmedAsBall = stashed && stashedAlong == nil
        let handleOpacity = dimmedAsBall ? (reduceTransparency ? 0.75 : 0.45) : 1.0

        let hitRect: CGRect
        let glassSize: CGSize
        let glassOffset: CGSize
        let chevronOffset: CGSize
        if let along = stashedAlong, stashedAsTab {
            let totalThickness = stashVisibleThickness + stashOverhang
            let glassShift = (stashVisibleThickness - stashOverhang - stashHitThickness) / 2
            let chevronShift = stashOverhang / 2
            switch anchor.edge {
            case .left:
                hitRect = CGRect(x: 0, y: along - half, width: stashHitThickness, height: itemSize)
                glassSize = CGSize(width: totalThickness, height: itemSize)
                glassOffset = CGSize(width: glassShift, height: 0)
                chevronOffset = CGSize(width: chevronShift, height: 0)
            case .right:
                hitRect = CGRect(x: size.width - stashHitThickness, y: along - half, width: stashHitThickness, height: itemSize)
                glassSize = CGSize(width: totalThickness, height: itemSize)
                glassOffset = CGSize(width: -glassShift, height: 0)
                chevronOffset = CGSize(width: -chevronShift, height: 0)
            case .top:
                hitRect = CGRect(x: along - half, y: 0, width: itemSize, height: stashHitThickness)
                glassSize = CGSize(width: itemSize, height: totalThickness)
                glassOffset = CGSize(width: 0, height: glassShift)
                chevronOffset = CGSize(width: 0, height: chevronShift)
            case .bottom:
                hitRect = CGRect(x: along - half, y: size.height - stashHitThickness, width: itemSize, height: stashHitThickness)
                glassSize = CGSize(width: itemSize, height: totalThickness)
                glassOffset = CGSize(width: 0, height: -glassShift)
                chevronOffset = CGSize(width: 0, height: -chevronShift)
            }
        } else {
            hitRect = CGRect(x: center.x - half, y: center.y - half, width: itemSize, height: itemSize)
            glassSize = CGSize(width: itemSize, height: itemSize)
            glassOffset = .zero
            chevronOffset = .zero
        }
        let shape = RoundedRectangle(cornerRadius: stashedAsTab ? stashCornerRadius : half, style: .continuous)

        return ZStack(alignment: .topLeading) {
            if controller.isToolbarVisible, dragPoint == nil {
                floatingBar(
                    plan: floatingPlan,
                    direction: placement.direction,
                    center: center,
                    barLeading: barLeading,
                    barTop: barTop,
                    half: half,
                    gap: gap,
                    margin: margin,
                    size: size
                )
            }
            Button {
                controller.tapToolbarHandle()
            } label: {
                ZStack {
                    ZStack {
                        AssistiveBallLabel(size: itemSize)
                            .opacity(stashedAsTab ? 0 : 1)
                            .scaleEffect(stashedAsTab ? 0.6 : 1)
                        Image(systemName: anchor.edge.stashChevronSymbol)
                            .font(.system(size: 15 * scale, weight: .semibold))
                            .foregroundStyle(.primary.opacity(0.85))
                            .offset(chevronOffset)
                            .opacity(stashedAsTab ? 1 : 0)
                    }
                    .frame(width: glassSize.width, height: glassSize.height)
                    .glassEffectCompat(tint: glassTint, in: shape)
                    .contentShape(.hoverEffect, shape)
                    .hoverEffect(.highlight)
                    .offset(glassOffset)
                }
                .frame(width: hitRect.width, height: hitRect.height)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .opacity(handleOpacity)
            .accessibilityLabel(Text(controller.isToolbarVisible ? "Hide Toolbar" : "Show Toolbar"))
            .allowsHitTesting(!controller.isPointerLocked)
            .onGeometryChangeCompat(for: CGRect.self) { $0.frame(in: .global) } action: { controller.toolbarHandleFrame = $0 }
            .highPriorityGesture(
                DragGesture(minimumDistance: 6, coordinateSpace: .named(Self.coordinateSpaceName))
                    .updating($isDragging) { _, state, _ in state = true }
                    .onChanged { value in
                        let start = dragStart ?? CGPoint(x: hitRect.midX, y: hitRect.midY)
                        if dragStart == nil {
                            dragStart = start
                            if controller.isToolbarStashed {
                                withAnimation(.smoothCompat(duration: 0.2)) {
                                    controller.isToolbarStashed = false
                                }
                            }
                        }
                        dragPoint = FloatingToolbarAnchor.clampBall(
                            CGPoint(x: start.x + value.translation.width, y: start.y + value.translation.height),
                            in: size,
                            ballSize: itemSize,
                            obstructions: obstructions
                        )
                    }
                    .onEnded { value in
                        let start = dragStart ?? placement.ballCenter
                        let projected = CGPoint(
                            x: start.x + value.translation.width + (value.predictedEndTranslation.width - value.translation.width) * 0.2,
                            y: start.y + value.translation.height + (value.predictedEndTranslation.height - value.translation.height) * 0.2
                        )
                        finishDrag(at: projected, in: size, obstructions: obstructions)
                    }
            )
            .onChangeCompat(of: isDragging) { dragging in
                if !dragging, let point = dragPoint {
                    finishDrag(at: point, in: size, obstructions: obstructions)
                }
            }
            .position(x: hitRect.midX, y: hitRect.midY)
            .frame(width: size.width, height: size.height)
            .clipped()
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
    }

    @ViewBuilder
    private func floatingBar(
        plan: ToolbarPlan,
        direction: FloatingToolbarDirection,
        center: CGPoint,
        barLeading: CGFloat,
        barTop: CGFloat,
        half: CGFloat,
        gap: CGFloat,
        margin: CGFloat,
        size: CGSize
    ) -> some View {
        let bar = SessionCommandBar(
            controller: controller,
            plan: plan,
            axis: direction.axis,
            onClose: closes ? onClose : nil
        )
        .environment(\.layoutDirection, layoutDirection)
        .onGeometryChangeCompat(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
            if controller.isToolbarVisible { controller.toolbarBarFrame = frame }
        }
        .transition(.scale(scale: 0.85, anchor: direction.transitionAnchor).combined(with: .opacity))

        switch direction {
        case .right:
            bar
                .padding(.leading, max(margin, center.x + half + gap))
                .padding(.top, max(0, barTop))
                .frame(width: size.width, height: size.height, alignment: .topLeading)
        case .left:
            bar
                .padding(.trailing, max(margin, size.width - (center.x - half - gap)))
                .padding(.top, max(0, barTop))
                .frame(width: size.width, height: size.height, alignment: .topTrailing)
        case .down:
            bar
                .padding(.leading, max(0, barLeading))
                .padding(.top, max(margin, center.y + half + gap))
                .frame(width: size.width, height: size.height, alignment: .topLeading)
        case .up:
            bar
                .padding(.leading, max(0, barLeading))
                .padding(.bottom, max(margin, size.height - (center.y - half - gap)))
                .frame(width: size.width, height: size.height, alignment: .bottomLeading)
        }
    }

    private func finishDrag(at point: CGPoint, in size: CGSize, obstructions: ScreenObstructions) {
        let anchor = FloatingToolbarAnchor.snap(point: point, in: size, ballSize: itemSize, obstructions: obstructions)
        dragStart = nil
        withAnimation(.smoothCompat(duration: 0.25)) {
            settings.floatingToolbarAnchor = anchor
            dragPoint = nil
        }
    }
    #endif
}

#if os(iOS)
private struct AssistiveBallLabel: View {
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(.primary.opacity(0.20))
                .frame(width: size * (28.0 / 44.0), height: size * (28.0 / 44.0))
            Circle()
                .fill(.primary.opacity(0.38))
                .frame(width: size * (21.0 / 44.0), height: size * (21.0 / 44.0))
            Circle()
                .fill(.primary.opacity(0.85))
                .frame(width: size * (14.0 / 44.0), height: size * (14.0 / 44.0))
        }
    }
}
#endif

enum SessionBarStyle {
    case glass
    case flat

    static var flatItemShape: some Shape {
        RoundedRectangle(cornerRadius: 15, style: .continuous).inset(by: 4)
    }
}

nonisolated private struct SessionBarStyleKey: EnvironmentKey {
    static let defaultValue = SessionBarStyle.glass
}

nonisolated extension EnvironmentValues {
    var sessionBarStyle: SessionBarStyle {
        get { self[SessionBarStyleKey.self] }
        set { self[SessionBarStyleKey.self] = newValue }
    }
}

struct SessionCommandBar: View {
    let controller: SessionController
    let plan: ToolbarPlan
    var axis = Axis.horizontal
    var style = SessionBarStyle.glass
    var onClose: (() -> Void)?

    @ScaledMetric(relativeTo: .body) private var itemSize = ToolbarLayout.itemSize
    @Environment(\.colorScheme) private var colorScheme

    private var glassTint: Color? {
        colorScheme == .dark ? .black.opacity(0.4) : nil
    }

    var body: some View {
        WithPerceptionTracking {
            GlassEffectContainerCompat(spacing: 0) {
                AxisStack(axis, spacing: style == .glass ? ToolbarLayout.capsuleGroupSpacing : 0) {
                    if let onClose {
                        backdrop(
                            Button(role: .closeCompat, action: onClose) {
                                Label("Disconnect", systemImage: "xmark")
                            }
                            .accessibilityShowsLargeContentViewer(),
                            in: Circle())
                    }
                    if !plan.inBar.isEmpty {
                        backdrop(
                            AxisStack(axis, spacing: 0) {
                                ForEach(plan.inBar) { command in
                                    SessionCommandView(command: command, controller: controller, style: .barItem)
                                        .accessibilityShowsLargeContentViewer()
                                        #if os(macOS)
                                        .help(String(localized: command.title))
                                        #endif
                                }
                            }
                            .padding(axis == .vertical ? .vertical : .horizontal, style == .glass ? ToolbarLayout.capsuleGroupPadding : 0),
                            in: Capsule())
                    }
                    #if os(iOS)
                    backdrop(
                        Menu {
                            SessionMoreMenuContent(controller: controller)
                        } label: {
                            Label("More", systemImage: "ellipsis")
                        }
                        .resolutionPopover(
                            controller: controller,
                            isPresented: Binding(
                                get: { controller.isShowingResolution && !plan.inBar.contains(.resolution) },
                                set: { controller.isShowingResolution = $0 }))
                        .accessibilityShowsLargeContentViewer(),
                        in: Circle())
                    #else
                    backdrop(
                        Button {
                            controller.isCustomizingToolbar = true
                        } label: {
                            Label("Customize Toolbar…", systemImage: "slider.horizontal.3")
                        }
                        .help("Customize Toolbar"),
                        in: Circle())
                    #endif
                }
            }
            .menuOrderFixedCompat()
            .labelStyle(ItemLabelStyle(size: itemSize, style: style))
            .environment(\.sessionBarStyle, style)
            .buttonStyle(.plain)
            #if os(iOS)
            .font(.body.weight(.medium))
            #else
            .menuIndicator(.hidden)
            .font(.title2.weight(.medium))
            #endif
            .fixedSize()
        }
    }

    @ViewBuilder
    private func backdrop(_ content: some View, in shape: some Shape) -> some View {
        switch style {
        case .glass: content.glassEffectCompat(tint: glassTint, in: shape)
        case .flat: content
        }
    }

    private struct ItemLabelStyle: LabelStyle {
        let size: CGFloat
        let style: SessionBarStyle

        func makeBody(configuration: Configuration) -> some View {
            let label = Label(configuration)
                .labelStyle(.iconOnly)
                .frame(width: size, height: size)
                .contentShape(Rectangle())
            #if os(iOS)
            switch style {
            case .glass: label.contentShape(.hoverEffect, Circle()).hoverEffect(.highlight)
            case .flat: label.contentShape(.hoverEffect, SessionBarStyle.flatItemShape).hoverEffect(.highlight)
            }
            #else
            label
            #endif
        }
    }
}
