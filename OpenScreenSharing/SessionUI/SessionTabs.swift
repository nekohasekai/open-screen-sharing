#if os(iOS)
import Perception
import SwiftUI

@Perceptible
final class SessionTabs {
    static var isOffered: Bool {
        guard #available(iOS 17, *) else { return false }
        return UIDevice.current.userInterfaceIdiom == .pad
    }

    private(set) var sessions: [SessionController] = []
    var selected: SessionController?

    func open(_ route: SessionRoute) {
        if let computerID = route.computerID, let session = sessions.first(where: { $0.route.computerID == computerID }) {
            selected = session
            return
        }
        let session = SessionController(route: route)
        sessions.append(session)
        selected = session
    }

    func remove(_ session: SessionController) {
        guard let index = sessions.firstIndex(where: { $0 === session }) else { return }
        sessions.remove(at: index)
        if selected === session {
            selected = sessions.indices.contains(index) ? sessions[index] : sessions.last
        }
    }
}

@available(iOS 17, *)
struct SessionTabsView: View {
    let tabs: SessionTabs

    var body: some View {
        WithPerceptionTracking {
            let selected = tabs.selected
            VStack(spacing: 0) {
                if let selected {
                    SessionTabBar(tabs: tabs, selected: selected)
                }
                ZStack {
                    ForEach(tabs.sessions) { session in
                        SessionView(controller: session, hasOwnWindow: false, isSelectedTab: session === selected) {
                            tabs.remove(session)
                        }
                        .opacity(session === selected ? 1 : 0)
                        .allowsHitTesting(session === selected)
                        .accessibilityHidden(session !== selected)
                    }
                }
            }
            .background(Color.black.ignoresSafeArea())
        }
    }
}

@available(iOS 17, *)
private struct SessionTabBar: View {
    let tabs: SessionTabs
    let selected: SessionController

    @ScaledMetric(relativeTo: .body) private var itemSize = ToolbarLayout.itemSize
    @State private var width: CGFloat = 0
    private let store = ComputerStore.shared

    private static let minimumTabWidth: CGFloat = 120
    private static let maximumTabWidth: CGFloat = 240
    private static let margin: CGFloat = 8

    var body: some View {
        WithPerceptionTracking {
            let plan = ToolbarLayout.layout(for: selected)
                .plan(for: selected, slots: ToolbarLayout.tabBarSlots(width: width, itemWidth: itemSize))
            HStack(spacing: ToolbarLayout.capsuleGroupSpacing) {
                GeometryReader { proxy in
                    WithPerceptionTracking {
                        strip(width: proxy.size.width)
                    }
                }
                SessionCommandBar(controller: selected, plan: plan, style: .flat)
            }
            .frame(height: itemSize)
            .padding(.trailing, Self.margin)
            .containerCornerOffsetCompat(.leading)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .environment(\.colorScheme, .dark)
        }
    }

    private func strip(width: CGFloat) -> some View {
        let sessions = tabs.sessions
        let tabWidth = min(
            max((width - Self.margin - itemSize) / CGFloat(max(sessions.count, 1)), Self.minimumTabWidth), Self.maximumTabWidth)
        let unopened = store.computers.filter { computer in !sessions.contains { $0.route.computerID == computer.id } }
        return ScrollViewReader { scroll in
            ScrollView(.horizontal) {
                HStack(spacing: 0) {
                    ForEach(Array(sessions.enumerated()), id: \.element.id) { index, session in
                        let next = sessions.indices.contains(index + 1) ? sessions[index + 1] : nil
                        SessionTab(session: session, isSelected: session === selected, showsSeparator: session !== selected && next !== selected) {
                            tabs.selected = session
                        }
                        .frame(width: tabWidth)
                        .id(session.id)
                    }
                    Menu {
                        ForEach(unopened) { computer in
                            Button {
                                tabs.open(SessionRoute(computer: computer))
                            } label: {
                                Label(computer.displayName, systemImage: computer.device.systemImage)
                            }
                        }
                    } label: {
                        Label("New Connection", systemImage: "plus")
                            .labelStyle(.iconOnly)
                            .font(.body.weight(.medium))
                            .frame(width: itemSize, height: itemSize)
                            .contentShape(Rectangle())
                            .contentShape(.hoverEffect, Circle())
                            .hoverEffect(.highlight)
                    }
                    .menuOrder(.fixed)
                    .disabled(unopened.isEmpty)
                }
                .buttonStyle(.plain)
                .frame(height: itemSize)
                .padding(.leading, Self.margin)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            .onChange(of: selected.id, initial: true) {
                scroll.scrollTo(selected.id)
            }
        }
    }
}

@available(iOS 17, *)
private struct SessionTab: View {
    let session: SessionController
    let isSelected: Bool
    let showsSeparator: Bool
    let select: () -> Void

    var body: some View {
        WithPerceptionTracking {
            Button(action: select) {
                HStack(spacing: 8) {
                    icon
                        .frame(width: 22)
                    Text(verbatim: session.title)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(isSelected ? .primary : .secondary)
                .padding(.leading, 14)
                .padding(.trailing, 38)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background {
                    if isSelected {
                        Capsule().fill(Color.white.opacity(0.16))
                    }
                }
                .contentShape(Rectangle())
                .contentShape(.hoverEffect, Capsule())
                .hoverEffect(.highlight)
            }
            .accessibilityAddTraits(isSelected ? .isSelected : [])
            .overlay(alignment: .trailing) {
                Button(action: session.close) {
                    Label("Disconnect", systemImage: "xmark")
                        .labelStyle(.iconOnly)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 26, height: 26)
                        .contentShape(Circle())
                        .hoverEffect(.highlight)
                }
                .padding(.trailing, 9)
            }
            .overlay(alignment: .trailing) {
                if showsSeparator {
                    Capsule()
                        .fill(.quaternary)
                        .frame(width: 1, height: 16)
                }
            }
        }
    }

    @ViewBuilder
    private var icon: some View {
        switch session.phase {
        case .connected:
            Image(systemName: DeviceAppearance(model: session.route.model ?? "").systemImage)
        case .failed, .disconnected:
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.yellow)
        default:
            ProgressView()
                .controlSize(.small)
        }
    }
}
#endif
