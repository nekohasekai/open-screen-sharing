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
    let controller: SessionController
    let plan: ToolbarPlan
    let dock: ToolbarDock?
    let closes: Bool
    let onClose: () -> Void

    @ScaledMetric(relativeTo: .body) private var itemSize = ToolbarLayout.itemSize
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.layoutDirection) private var layoutDirection

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
        return bar(left ? .vertical : .horizontal)
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
        VStack(spacing: 0) {
            Button {
                controller.toggleToolbar()
            } label: {
                Image(systemName: controller.isToolbarVisible ? "chevron.compact.up" : "chevron.compact.down")
                    .font(.system(size: 12, weight: .heavy))
                    .frame(width: 34, height: 14)
                    .glassEffectCompat(tint: glassTint, in: Capsule())
                    .padding(.top, 8)
                    .frame(width: 88, height: 44, alignment: .top)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .hoverEffect()
            .accessibilityLabel(Text(controller.isToolbarVisible ? "Hide Toolbar" : "Show Toolbar"))
            .allowsHitTesting(!controller.isPointerLocked)
            .onGeometryChangeCompat(for: CGRect.self) { $0.frame(in: .global) } action: { controller.toolbarHandleFrame = $0 }
            if controller.isToolbarVisible {
                bar(.horizontal)
                    .onGeometryChangeCompat(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
                        if controller.isToolbarVisible { controller.toolbarBarFrame = frame }
                    }
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            if controller.showsUnlockBanner {
                UnlockBanner(controller: controller)
                    .padding(.top, 10)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(UnlockBanner.animation, value: controller.showsUnlockBanner)
        .onDisappear { controller.toolbarHandleFrame = nil }
    }
    #endif

    private func bar(_ axis: Axis) -> some View {
        GlassEffectContainerCompat(spacing: 0) {
            AxisStack(axis, spacing: ToolbarLayout.capsuleGroupSpacing) {
                if closes {
                    Button(role: .closeCompat, action: onClose) {
                        Label("Disconnect", systemImage: "xmark")
                    }
                    .accessibilityShowsLargeContentViewer()
                    .glassEffectCompat(tint: glassTint, in: Circle())
                }
                if !plan.inBar.isEmpty {
                    AxisStack(axis, spacing: 0) {
                        ForEach(plan.inBar) { command in
                            SessionCommandView(command: command, controller: controller, style: .barItem)
                                .accessibilityShowsLargeContentViewer()
                                #if os(macOS)
                                .help(String(localized: command.title))
                                #endif
                        }
                    }
                    .padding(axis == .vertical ? .vertical : .horizontal, ToolbarLayout.capsuleGroupPadding)
                    .glassEffectCompat(tint: glassTint, in: Capsule())
                }
                #if os(iOS)
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
                .accessibilityShowsLargeContentViewer()
                .glassEffectCompat(tint: glassTint, in: Circle())
                #else
                Button {
                    controller.isCustomizingToolbar = true
                } label: {
                    Label("Customize Toolbar…", systemImage: "slider.horizontal.3")
                }
                .help("Customize Toolbar")
                .glassEffectCompat(tint: glassTint, in: Circle())
                #endif
            }
        }
        .menuOrderFixedCompat()
        .labelStyle(ItemLabelStyle(size: itemSize))
        .buttonStyle(.plain)
        #if os(iOS)
        .font(.body.weight(.medium))
        #else
        .menuIndicator(.hidden)
        .font(.title2.weight(.medium))
        #endif
        .fixedSize()
    }

    struct ItemLabelStyle: LabelStyle {
        let size: CGFloat

        func makeBody(configuration: Configuration) -> some View {
            Label(configuration)
                .labelStyle(.iconOnly)
                .frame(width: size, height: size)
                .contentShape(Rectangle())
                #if os(iOS)
                .contentShape(.hoverEffect, Circle())
                .hoverEffect(.highlight)
                #endif
        }
    }
}
