import SwiftUI
#if os(iOS)
import UIKit
#endif

struct NavigationStackCompat<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        if #available(iOS 17, *) {
            NavigationStackWithPath {
                content
            }
        } else {
            NavigationView {
                content
            }
            #if os(iOS)
            .navigationViewStyle(.stack)
            #endif
        }
    }
}

@available(iOS 16, *)
private struct NavigationStackWithPath<Content: View>: View {
    private let content: Content
    @State private var path = NavigationPath()

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        NavigationStack(path: $path) {
            content
        }
    }
}

struct LabeledContentCompat<Label: View, Content: View>: View {
    private let label: Label
    private let content: Content

    init(@ViewBuilder content: () -> Content, @ViewBuilder label: () -> Label) {
        self.content = content()
        self.label = label()
    }

    var body: some View {
        #if os(iOS)
        if #available(iOS 16, *) {
            LabeledContent {
                content
            } label: {
                label
            }
        } else {
            HStack {
                label
                Spacer()
                content
                    .foregroundStyle(.secondary)
            }
        }
        #else
        LabeledContent {
            content
                .labelsHidden()
        } label: {
            label
        }
        #endif
    }
}

extension LabeledContentCompat where Label == Text {
    init(_ titleKey: LocalizedStringKey, @ViewBuilder content: () -> Content) {
        self.init(content: content) { Text(titleKey) }
    }
}

extension LabeledContentCompat where Label == Text, Content == Text {
    init(_ titleKey: LocalizedStringKey, value: String) {
        self.init(titleKey) { Text(value) }
    }
}

struct ContentUnavailableViewCompat<Label: View, Description: View>: View {
    private let label: Label
    private let description: Description

    init(@ViewBuilder label: () -> Label, @ViewBuilder description: () -> Description) {
        self.label = label()
        self.description = description()
    }

    var body: some View {
        #if os(macOS)
        ContentUnavailableView {
            label
        } description: {
            description
        }
        #else
        if #available(iOS 17, *) {
            ContentUnavailableView {
                label
            } description: {
                description
            }
        } else {
            VStack(spacing: 8) {
                label
                    .labelStyle(UnavailableLabelStyle())
                description
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        #endif
    }
}

private struct UnavailableLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        VStack(spacing: 16) {
            configuration.icon
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            configuration.title
                .font(.title2.bold())
        }
        .multilineTextAlignment(.center)
    }
}

extension View {
    @ViewBuilder
    func navigationDestinationCompat(isPresented: Binding<Bool>, @ViewBuilder destination: () -> some View) -> some View {
        let destination = destination()
        if #available(iOS 17, *) {
            navigationDestination(isPresented: isPresented) {
                destination
            }
        } else {
            background {
                NavigationLink(destination: destination, isActive: isPresented) {
                    EmptyView()
                }
            }
        }
    }

    @ViewBuilder
    func primaryActionsCompat(@ViewBuilder leading: () -> some View, @ViewBuilder trailing: () -> some View) -> some View {
        let leading = leading()
        let trailing = trailing()
        if #available(iOS 26, macOS 26, *) {
            toolbar {
                ToolbarItem(placement: .primaryAction) {
                    leading
                }
                ToolbarSpacer(.fixed, placement: .primaryAction)
                ToolbarItem(placement: .primaryAction) {
                    trailing
                }
            }
        } else {
            toolbar {
                ToolbarItem(placement: .primaryAction) {
                    leading
                }
                ToolbarItem(placement: .primaryAction) {
                    trailing
                }
            }
        }
    }

    func onChangeCompat(of value: some Equatable, _ action: @escaping () -> Void) -> some View {
        #if os(iOS)
        onChange(of: value) { _ in
            action()
        }
        #else
        onChange(of: value, action)
        #endif
    }

    func onChangeCompat<V: Equatable>(of value: V, _ action: @escaping (_ newValue: V) -> Void) -> some View {
        #if os(iOS)
        onChange(of: value, perform: action)
        #else
        onChange(of: value) { action($1) }
        #endif
    }

    func onChangeCompat<V: Equatable>(of value: V, initial: Bool, _ action: @escaping (_ newValue: V) -> Void) -> some View {
        #if os(iOS)
        onAppear {
            if initial {
                action(value)
            }
        }
        .onChange(of: value, perform: action)
        #else
        onChange(of: value, initial: initial) { action($1) }
        #endif
    }
}

extension View {
    func onGeometryChangeCompat<T: Equatable & Sendable>(
        for type: T.Type, of transform: @escaping @Sendable (GeometryProxy) -> T, action: @escaping (_ newValue: T) -> Void
    ) -> some View {
        if #available(iOS 16, *) {
            return onGeometryChange(for: type, of: transform, action: action)
        } else {
            return background {
                GeometryReader { proxy in
                    Color.clear
                        .onAppear { action(transform(proxy)) }
                        .onChange(of: transform(proxy), perform: action)
                }
            }
        }
    }

    @ViewBuilder
    func persistentSystemOverlaysCompat(_ visibility: Visibility) -> some View {
        if #available(iOS 16, *) {
            persistentSystemOverlays(visibility)
        } else {
            self
        }
    }

    #if os(iOS)
    @ViewBuilder
    func defersSystemGesturesCompat(on edges: Edge.Set) -> some View {
        if #available(iOS 16, *) {
            defersSystemGestures(on: edges)
        } else {
            self
        }
    }

    @ViewBuilder
    func presentationBackgroundCompat(_ style: some ShapeStyle) -> some View {
        if #available(iOS 16.4, *) {
            presentationBackground(style)
        } else {
            self
        }
    }
    #else
    @ViewBuilder
    func windowToolbarFullScreenVisibilityCompat() -> some View {
        if #available(macOS 15, *) {
            windowToolbarFullScreenVisibility(.onHover)
        } else {
            self
        }
    }
    #endif

    @ViewBuilder
    func popoverOnCompact() -> some View {
        if #available(iOS 16.4, *) {
            presentationCompactAdaptation(.popover)
        } else {
            self
        }
    }

    @ViewBuilder
    func selectionFeedbackCompat(trigger: some Equatable) -> some View {
        if #available(iOS 17, *) {
            sensoryFeedback(.selection, trigger: trigger)
        } else {
            #if os(iOS)
            onChange(of: trigger) { _ in
                UISelectionFeedbackGenerator().selectionChanged()
            }
            #else
            self
            #endif
        }
    }

    @ViewBuilder
    func hoverEffectDisabledCompat() -> some View {
        #if os(iOS)
        if #available(iOS 17, *) {
            hoverEffectDisabled()
        } else {
            self
        }
        #else
        self
        #endif
    }

    @ViewBuilder
    func menuOrderFixedCompat() -> some View {
        if #available(iOS 16, *) {
            menuOrder(.fixed)
        } else {
            self
        }
    }

    @ViewBuilder
    func glassEffectCompat(tint: Color? = nil, interactive: Bool = false, in shape: some Shape) -> some View {
        if #available(iOS 26, macOS 26, *) {
            let glass = tint.map { Glass.regular.tint($0) } ?? .regular
            glassEffect(interactive ? glass.interactive() : glass, in: shape)
        } else {
            background(.regularMaterial, in: shape)
        }
    }

    @ViewBuilder
    func glassButtonStyleCompat() -> some View {
        if #available(iOS 26, macOS 26, *) {
            buttonStyle(.glass)
        } else {
            buttonStyle(.bordered)
        }
    }

    @ViewBuilder
    func formRowButtonStyleCompat() -> some View {
        #if os(macOS)
        buttonStyle(FormRowButtonStyle())
        #else
        self
        #endif
    }
}

#if os(macOS)
private struct FormRowButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let foreground: AnyShapeStyle =
            if !isEnabled {
                AnyShapeStyle(.tertiary)
            } else if configuration.role == .destructive {
                AnyShapeStyle(.red)
            } else {
                AnyShapeStyle(.tint)
            }
        configuration.label
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

struct WithPerceptionTracking<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: Content { content() }
}
#endif

struct GlassEffectContainerCompat<Content: View>: View {
    private let spacing: CGFloat?
    private let content: Content

    init(spacing: CGFloat? = nil, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }

    var body: some View {
        if #available(iOS 26, macOS 26, *) {
            GlassEffectContainer(spacing: spacing) {
                content
            }
        } else {
            content
        }
    }
}

struct AxisStack<Content: View>: View {
    private let axis: Axis
    private let spacing: CGFloat?
    private let content: Content

    init(_ axis: Axis, spacing: CGFloat? = nil, @ViewBuilder content: () -> Content) {
        self.axis = axis
        self.spacing = spacing
        self.content = content()
    }

    var body: some View {
        if #available(iOS 16, *) {
            let layout = axis == .vertical ? AnyLayout(VStackLayout(spacing: spacing)) : AnyLayout(HStackLayout(spacing: spacing))
            layout {
                content
            }
        } else if axis == .vertical {
            VStack(spacing: spacing) {
                content
            }
        } else {
            HStack(spacing: spacing) {
                content
            }
        }
    }
}

struct CloseButton: View {
    let action: () -> Void

    var body: some View {
        if #available(iOS 26, macOS 26, *) {
            Button(role: .close, action: action)
        } else {
            Button("Close", action: action)
        }
    }
}

extension ButtonRole {
    static var confirmCompat: ButtonRole? {
        if #available(iOS 26, macOS 26, *) {
            .confirm
        } else {
            nil
        }
    }

    static var closeCompat: ButtonRole? {
        if #available(iOS 26, macOS 26, *) {
            .close
        } else {
            nil
        }
    }
}

extension Animation {
    static func smoothCompat(duration: TimeInterval) -> Animation {
        if #available(iOS 17, *) {
            .smooth(duration: duration)
        } else {
            .easeInOut(duration: duration)
        }
    }
}
