import SwiftUI

enum SessionTutorial: String, CaseIterable, Codable, Identifiable {
    case pressToDrag
    case pointerLock
    case dictation
    case customInput
    case nativeTouch

    var id: Self { self }

    #if os(iOS)
    static var unseen: [SessionTutorial] {
        guard Demo.current == nil else { return [] }
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        return allCases.filter { tutorial in
            guard !AppSettings.shared.seenTutorials.contains(tutorial) else { return false }
            switch tutorial {
            case .pressToDrag, .customInput, .nativeTouch: return true
            case .pointerLock, .dictation: return isPad
            }
        }
    }
    #endif
}

#if os(iOS)
struct SessionTutorialView: View {
    let pages: [SessionTutorial]
    let onDone: () -> Void
    private let settings = AppSettings.shared
    @State private var index = 0
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    var body: some View {
        let page = pages[index]
        ZStack {
            Color.black.opacity(0.45)
                .ignoresSafeArea()
            Group {
                if horizontalSizeClass == .compact && verticalSizeClass == .regular {
                    VStack(alignment: .leading, spacing: 16) {
                        illustration(page)
                        text(page)
                        button(page)
                    }
                    .frame(width: 310)
                } else {
                    HStack(alignment: .top, spacing: 24) {
                        illustration(page)
                        VStack(alignment: .leading, spacing: 0) {
                            text(page)
                            Spacer(minLength: 16)
                            button(page)
                        }
                        .frame(width: 244)
                        .frame(maxHeight: .infinity)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(20)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
            .dynamicTypeSize(...DynamicTypeSize.xxLarge)
            .padding(16)
        }
    }

    private func illustration(_ page: SessionTutorial) -> some View {
        VStack(spacing: 14) {
            Group {
                switch page {
                case .pressToDrag: PressToDragIllustration()
                case .pointerLock: PointerLockIllustration()
                case .dictation: DictationIllustration()
                case .customInput: CustomInputIllustration()
                case .nativeTouch: NativeTouchIllustration()
                }
            }
            .frame(width: 310, height: 210)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .environment(\.colorScheme, .dark)
            .accessibilityHidden(true)
            .id(page)
            if pages.count > 1 {
                HStack(spacing: 8) {
                    ForEach(pages.indices, id: \.self) { i in
                        Circle()
                            .fill(i == index ? Color.primary : Color.secondary.opacity(0.4))
                            .frame(width: 7, height: 7)
                    }
                }
            }
        }
    }

    private func text(_ page: SessionTutorial) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            switch page {
            case .pressToDrag:
                Text("Press Gesture")
                    .font(.title2.bold())
                Text("Touch and hold anywhere on the screen for a while to simulate a press.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            case .pointerLock:
                Text("Pointer Lock")
                    .font(.title2.bold())
                Text(
                    "When using a mouse or trackpad, turn on \(String(localized: SessionCommandCategory.control.title)) › \(String(localized: SessionCommand.pointerLock.title)) so moving to the edge of the screen won’t open the Dock or Control Center."
                )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            case .dictation:
                Text("Dictation")
                    .font(.title2.bold())
                Text(
                    "When Magic Keyboard is connected, you can also use \(Image(systemName: "mic")) Dictation and the \(Image(systemName: "globe")) emoji keyboard on this iPad."
                )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            case .customInput:
                Text("Custom Keys and Text")
                    .font(.title2.bold())
                Text(
                    "Set up a keyboard shortcut in \(String(localized: "Settings")) › \(String(localized: "Keyboard")), or add an item in \(String(localized: "Settings")) › \(String(localized: SessionCommand.quickInput.title)) and choose it from the Quick Input menu, to send a custom sequence of keys and text to the connected device."
                )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            case .nativeTouch:
                Text("Native Touch for Mac")
                    .font(.title2.bold())
                Group {
                    if SessionMemory.defaultUsesNativeTouch {
                        Text("Native touch is supported when you connect with Apple VNC to a Mac with macOS 27.3 or later.")
                    } else {
                        Text(
                            "When you connect with Apple VNC to a Mac with macOS 27.3 or later, you can turn on \(String(localized: SessionCommandCategory.control.title)) › \(String(localized: SessionCommand.nativeTouch.title))."
                        )
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func button(_ page: SessionTutorial) -> some View {
        let isLast = index == pages.count - 1
        return Button {
            settings.seenTutorials.insert(page)
            if isLast {
                onDone()
            } else {
                withAnimation { index += 1 }
            }
        } label: {
            (isLast ? Text("OK") : Text("Next Tip"))
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .controlSize(.large)
    }
}

private enum TutorialPalette {
    static let wallpaper = Gradient(colors: [
        Color(red: 0x24 / 255, green: 0x47 / 255, blue: 0x6E / 255), Color(red: 0x5E / 255, green: 0x3B / 255, blue: 0x78 / 255),
    ])
    static let window = Color(red: 0xF5 / 255, green: 0xF5 / 255, blue: 0xF7 / 255)
    static let line = Color(red: 0xD1 / 255, green: 0xD1 / 255, blue: 0xD6 / 255)
    static let lights = [
        Color(red: 1, green: 0x5F / 255, blue: 0x57 / 255), Color(red: 0xFE / 255, green: 0xBC / 255, blue: 0x2E / 255),
        Color(red: 0x28 / 255, green: 0xC8 / 255, blue: 0x40 / 255),
    ]

    static func drawPointer(_ context: inout GraphicsContext, at center: CGPoint, radius: CGFloat) {
        let circle = Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
        context.fill(circle, with: .color(Color(white: 0.28).opacity(0.62)))
        context.stroke(circle, with: .color(.white.opacity(0.55)), lineWidth: 0.8)
    }

    static func drawFinger(_ context: inout GraphicsContext, at center: CGPoint, radius: CGFloat) {
        let circle = Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
        context.fill(circle, with: .color(.white.opacity(0.35)))
        context.stroke(circle, with: .color(.white.opacity(0.9)), lineWidth: 1.5)
    }
}

private struct PressToDragIllustration: View {
    private static let size = CGSize(width: 310, height: 210)
    private static let period = 5.2
    private static let windowOrigin = CGPoint(x: 34, y: 44)
    private static let fingerOrigin = CGPoint(x: 196, y: 148)
    private static let travel = CGPoint(x: 84, y: 8)
    private static let pointerOffset = CGPoint(x: 72, y: 10)
    private static let pointerScale: CGFloat = 3
    private static let pointerHotspot = PointerShape.mask(for: .arrow, beamLength: 0, scale: pointerScale)?.hotspot ?? .zero
    private static let pointerImages = [false, true].map { increasesContrast in
        PointerShape.preview(
            for: .arrow, backdrop: SIMD3(0.89, 0.89, 0.91), material: CursorMaterial(isDarkInterface: false, increasesContrast: increasesContrast),
            scale: pointerScale)
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    @State private var start = Date()

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion)) { timeline in
            let time = reduceMotion ? 2.2 : timeline.date.timeIntervalSince(start).truncatingRemainder(dividingBy: Self.period)
            Canvas { context, size in
                let scale = size.width / Self.size.width
                context.scaleBy(x: scale, y: scale)
                draw(in: &context, size: Self.size, at: time)
            }
        }
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, at t: Double) {
        func progress(_ from: Double, _ to: Double) -> CGFloat { CGFloat(min(1, max(0, (t - from) / (to - from)))) }
        func eased(_ x: CGFloat) -> CGFloat { x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2 }

        let move = eased(progress(1.45, 2.75))
        let lift = progress(3.1, 3.3)
        let press = progress(0.8, 0.9) - progress(3.1, 3.2)
        let reset = t >= 4.6
        let fade = reset ? progress(4.6, 4.9) : 1 - progress(4.3, 4.6)
        let offset =
            reset ? .zero : CGPoint(x: Self.travel.x * move, y: Self.travel.y * move - 12 * sin(.pi * move))

        context.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .linearGradient(TutorialPalette.wallpaper, startPoint: .zero, endPoint: CGPoint(x: size.width, y: size.height)))
        context.fill(Path(CGRect(x: 0, y: 0, width: size.width, height: 14)), with: .color(.white.opacity(0.2)))

        let window = CGPoint(x: Self.windowOrigin.x + offset.x, y: Self.windowOrigin.y + offset.y)
        var windowContext = context
        windowContext.opacity = fade
        windowContext.addFilter(.shadow(color: .black.opacity(0.3 + 0.2 * press), radius: 3 + 4 * press, x: 0, y: 3 + 6 * press))
        windowContext.drawLayer { layer in
            let frame = CGRect(x: window.x, y: window.y, width: 132, height: 88)
            let shape = Path(roundedRect: frame, cornerRadius: 8, style: .continuous)
            layer.clip(to: shape)
            layer.fill(shape, with: .color(TutorialPalette.window))
            let bar = (227 - 19 * press) / 255
            layer.fill(
                Path(CGRect(x: frame.minX, y: frame.minY, width: frame.width, height: 19)),
                with: .color(Color(red: bar, green: bar, blue: (232 - 16 * press) / 255)))
            for (i, color) in TutorialPalette.lights.enumerated() {
                let x = frame.minX + 11 + CGFloat(i) * 10
                layer.fill(Path(ellipseIn: CGRect(x: x - 3.3, y: frame.minY + 6.2, width: 6.6, height: 6.6)), with: .color(color))
            }
            for (y, width) in [(32.0, 90.0), (44, 108), (56, 70), (68, 96)] {
                layer.fill(
                    Path(roundedRect: CGRect(x: frame.minX + 12, y: frame.minY + y, width: width, height: 5), cornerRadius: 2.5),
                    with: .color(TutorialPalette.line))
            }
        }

        let pointer = CGPoint(x: window.x + Self.pointerOffset.x, y: window.y + Self.pointerOffset.y)
        var pointerCenter = pointer
        if let image = Self.pointerImages[colorSchemeContrast == .increased ? 1 : 0] {
            let rect = CGRect(
                x: pointer.x - Self.pointerHotspot.x, y: pointer.y - Self.pointerHotspot.y,
                width: CGFloat(image.width) / Self.pointerScale, height: CGFloat(image.height) / Self.pointerScale)
            pointerCenter = CGPoint(x: rect.midX, y: rect.midY)
            var pointerContext = context
            pointerContext.opacity = fade
            pointerContext.draw(Image(decorative: image, scale: Self.pointerScale), in: rect)
        }

        func circle(_ center: CGPoint, _ radius: CGFloat) -> Path {
            Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
        }
        func drawIndicator(opacity: CGFloat, hold: CGFloat, pulse: CGFloat?) {
            var indicator = context
            indicator.opacity = opacity
            indicator.fill(circle(pointerCenter, 20), with: .color(.white.opacity(0.2 * press)))
            indicator.stroke(circle(pointerCenter, 20), with: .color(.black.opacity(0.3)), lineWidth: 3)
            if let pulse {
                indicator.stroke(circle(pointerCenter, 20 * (1 + 0.4 * pulse)), with: .color(.white.opacity(1 - pulse)), lineWidth: 2)
            }
            var ring = Path()
            ring.addArc(
                center: pointerCenter, radius: 20, startAngle: .degrees(-90), endAngle: .degrees(-90 + 360 * Double(hold)), clockwise: false)
            indicator.addFilter(.shadow(color: .black.opacity(0.4), radius: 2))
            indicator.stroke(ring, with: .color(.white), style: StrokeStyle(lineWidth: 3, lineCap: .round))
        }
        func drawFinger(opacity: CGFloat, scale: CGFloat) {
            var finger = context
            finger.opacity = opacity
            TutorialPalette.drawFinger(
                &finger, at: CGPoint(x: Self.fingerOrigin.x + offset.x, y: Self.fingerOrigin.y + offset.y), radius: 17 * scale)
        }

        if t >= 0.5, t < 3.3 {
            let appear = progress(0.5, 0.65)
            drawFinger(opacity: min(appear, 1 - lift), scale: t < 1 ? 1.25 - 0.25 * appear : 1 + 0.2 * lift)
        }
        if t >= 0.65, t < 3.3 {
            drawIndicator(
                opacity: min(progress(0.65, 0.7), 1 - lift), hold: progress(0.65, 0.8),
                pulse: t >= 0.8 && t < 1.15 ? progress(0.8, 1.15) : nil)
        }
    }
}

private struct NativeTouchIllustration: View {
    private static let size = CGSize(width: 310, height: 210)
    private static let period = 4.6
    private static let window = CGRect(x: 55, y: 36, width: 200, height: 146)
    private static let focus = CGPoint(x: 155, y: 125)
    private static let direction = CGPoint(x: 0.82, y: -0.57)
    private static let spread: (from: CGFloat, to: CGFloat) = (24, 43)
    private static let sky = Gradient(colors: [
        Color(red: 0x6B / 255, green: 0xA8 / 255, blue: 0xEC / 255), Color(red: 0xCD / 255, green: 0xE3 / 255, blue: 0xFA / 255),
    ])
    private static let sun = Color(red: 1, green: 0xCC / 255, blue: 0x5A / 255)
    private static let mountain = Color(red: 0x73 / 255, green: 0x84 / 255, blue: 0xB8 / 255)
    private static let hill = Color(red: 0x5C / 255, green: 0x9E / 255, blue: 0x6B / 255)

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date()

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion)) { timeline in
            let time = reduceMotion ? 2.1 : timeline.date.timeIntervalSince(start).truncatingRemainder(dividingBy: Self.period)
            Canvas { context, size in
                let scale = size.width / Self.size.width
                context.scaleBy(x: scale, y: scale)
                draw(in: &context, size: Self.size, at: time)
            }
        }
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, at t: Double) {
        func progress(_ from: Double, _ to: Double) -> CGFloat { CGFloat(min(1, max(0, (t - from) / (to - from)))) }
        func eased(_ x: CGFloat) -> CGFloat { x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2 }

        let reset = t >= 4
        let pinch = reset ? 0 : eased(progress(0.75, 1.95))
        let spread = Self.spread.from + (Self.spread.to - Self.spread.from) * pinch
        let zoom = spread / Self.spread.from
        let fade = reset ? progress(4, 4.3) : 1 - progress(3.7, 4)

        context.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .linearGradient(TutorialPalette.wallpaper, startPoint: .zero, endPoint: CGPoint(x: size.width, y: size.height)))
        context.fill(Path(CGRect(x: 0, y: 0, width: size.width, height: 14)), with: .color(.white.opacity(0.2)))

        var windowContext = context
        windowContext.addFilter(.shadow(color: .black.opacity(0.3), radius: 3, x: 0, y: 3))
        windowContext.drawLayer { layer in
            let frame = Self.window
            let shape = Path(roundedRect: frame, cornerRadius: 8, style: .continuous)
            layer.clip(to: shape)
            layer.fill(shape, with: .color(TutorialPalette.window))

            let content = CGRect(x: frame.minX, y: frame.minY + 19, width: frame.width, height: frame.height - 19)
            var photo = layer
            photo.clip(to: Path(content))
            photo.opacity = fade
            photo.translateBy(x: Self.focus.x, y: Self.focus.y)
            photo.scaleBy(x: zoom, y: zoom)
            photo.translateBy(x: content.minX - Self.focus.x, y: content.minY - Self.focus.y)
            let width = content.width
            let height = content.height
            photo.fill(
                Path(CGRect(origin: .zero, size: content.size)),
                with: .linearGradient(Self.sky, startPoint: .zero, endPoint: CGPoint(x: 0, y: height)))
            photo.fill(Path(ellipseIn: CGRect(x: 139, y: 19, width: 22, height: 22)), with: .color(Self.sun))
            var mountains = Path()
            mountains.addLines([
                CGPoint(x: 0, y: 88), CGPoint(x: 48, y: 40), CGPoint(x: 82, y: 70), CGPoint(x: 122, y: 30), CGPoint(x: 172, y: 76),
                CGPoint(x: width, y: 58), CGPoint(x: width, y: height), CGPoint(x: 0, y: height),
            ])
            mountains.closeSubpath()
            photo.fill(mountains, with: .color(Self.mountain))
            var hills = Path()
            hills.move(to: CGPoint(x: 0, y: 104))
            hills.addQuadCurve(to: CGPoint(x: 110, y: 96), control: CGPoint(x: 50, y: 80))
            hills.addQuadCurve(to: CGPoint(x: width, y: 92), control: CGPoint(x: 160, y: 108))
            hills.addLine(to: CGPoint(x: width, y: height))
            hills.addLine(to: CGPoint(x: 0, y: height))
            hills.closeSubpath()
            photo.fill(hills, with: .color(Self.hill))

            layer.fill(Path(CGRect(x: frame.minX, y: frame.minY, width: frame.width, height: 19)), with: .color(Color(white: 0.89)))
            for (i, color) in TutorialPalette.lights.enumerated() {
                let x = frame.minX + 11 + CGFloat(i) * 10
                layer.fill(Path(ellipseIn: CGRect(x: x - 3.3, y: frame.minY + 6.2, width: 6.6, height: 6.6)), with: .color(color))
            }
        }

        if t >= 0.4, t < 2.45 {
            let appear = progress(0.4, 0.55)
            let lift = progress(2.25, 2.45)
            var fingers = context
            fingers.opacity = min(appear, 1 - lift)
            let radius = 17 * (t < 1 ? 1.25 - 0.25 * appear : 1 + 0.2 * lift)
            for sign: CGFloat in [-1, 1] {
                TutorialPalette.drawFinger(
                    &fingers,
                    at: CGPoint(x: Self.focus.x + sign * Self.direction.x * spread, y: Self.focus.y + sign * Self.direction.y * spread),
                    radius: radius)
            }
        }
    }
}

private struct DictationIllustration: View {
    var body: some View {
        HStack(spacing: 14) {
            key("mic")
            key("globe")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(white: 0.17))
    }

    private func key(_ systemImage: String) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: 30, weight: .medium))
            .foregroundStyle(.white)
            .frame(width: 84, height: 76)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(white: 0.26)))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.accentColor, lineWidth: 2))
    }
}

private struct CustomInputIllustration: View {
    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 8) {
                key(Text(verbatim: "⌘"))
                key(Text(verbatim: "J"))
                Text(verbatim: "/")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 6)
                key(Text(Image(systemName: SessionCommand.quickInput.systemImage)))
            }
            Image(systemName: "arrow.down")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                ForEach(["⌃C", "“hello”", "↩\u{FE0E}"], id: \.self) { step in
                    Text(verbatim: step)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .frame(height: 40)
                        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(white: 0.26)))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(white: 0.17))
    }

    private func key(_ label: Text) -> some View {
        label
            .font(.system(size: 24, weight: .medium))
            .foregroundStyle(.white)
            .frame(width: 58, height: 54)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(white: 0.26)))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.accentColor, lineWidth: 2))
    }
}

private struct PointerLockIllustration: View {
    private static let toolbar: [SessionCommand] = [.audio, .pointerLock, .keyboard, .clipboard]

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                item("xmark")
                    .background(Circle().fill(Color(white: 0.24)))
                    .overlay(Circle().strokeBorder(.white.opacity(0.12)))
                HStack(spacing: 0) {
                    ForEach(Self.toolbar) { command in
                        if command == .pointerLock {
                            item(command.systemImage, highlighted: true)
                                .overlay(alignment: .top) {
                                    callout
                                        .fixedSize()
                                        .offset(y: 32)
                                }
                        } else {
                            item(command.systemImage)
                        }
                    }
                }
                .padding(.horizontal, 3)
                .background(Capsule().fill(Color(white: 0.24)))
                .overlay(Capsule().strokeBorder(.white.opacity(0.12)))
                item("ellipsis")
                    .background(Circle().fill(Color(white: 0.24)))
                    .overlay(Circle().strokeBorder(.white.opacity(0.12)))
            }
            .padding(.top, 12)
            .padding(.bottom, 27)
            HStack(spacing: 10) {
                tablet(locked: false)
                tablet(locked: true)
            }
            .padding(.top, 8)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(white: 0.17))
    }

    private var callout: some View {
        VStack(spacing: 0) {
            Path { path in
                path.move(to: CGPoint(x: 0, y: 6))
                path.addLine(to: CGPoint(x: 5, y: 0))
                path.addLine(to: CGPoint(x: 10, y: 6))
                path.closeSubpath()
            }
            .fill(Color.accentColor)
            .frame(width: 10, height: 6)
            Text("Pointer Lock")
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .frame(height: 19)
                .background(Capsule().fill(Color.accentColor))
        }
    }

    private func item(_ systemImage: String, highlighted: Bool = false) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(highlighted ? Color.white : Color(white: 0.85))
            .frame(width: 32, height: 32)
            .background {
                if highlighted {
                    Circle().fill(Color.accentColor).padding(1)
                }
            }
    }

    private func tablet(locked: Bool) -> some View {
        VStack(spacing: 5) {
            Canvas { context, size in
                drawScreen(in: &context, size: size, locked: locked)
            }
            .frame(width: 132, height: 90)
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            .padding(4)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(white: 0.04)))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color(white: 0.28)))
            Label {
                locked ? Text("On") : Text("Off")
            } icon: {
                Image(systemName: locked ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, locked ? Color.green : Color.red)
            }
            .font(.system(size: 11.5))
            .foregroundStyle(.secondary)
        }
    }

    private func drawScreen(in context: inout GraphicsContext, size: CGSize, locked: Bool) {
        context.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .linearGradient(TutorialPalette.wallpaper, startPoint: .zero, endPoint: CGPoint(x: size.width, y: size.height)))
        context.fill(Path(CGRect(x: 0, y: 0, width: size.width, height: 6)), with: .color(.white.opacity(0.2)))
        let window = Path(roundedRect: CGRect(x: 16, y: 16, width: 62, height: 40), cornerRadius: 4)
        context.fill(window, with: .color(TutorialPalette.window))
        var bar = context
        bar.clip(to: window)
        bar.fill(Path(CGRect(x: 16, y: 16, width: 62, height: 9)), with: .color(Color(white: 0.89)))
        for (i, color) in TutorialPalette.lights.enumerated() {
            context.fill(Path(ellipseIn: CGRect(x: 19.4 + CGFloat(i) * 5, y: 18.9, width: 3.2, height: 3.2)), with: .color(color))
        }
        for i in 0..<3 {
            context.fill(
                Path(ellipseIn: CGRect(x: 64.7, y: 58.7 + CGFloat(i) * 6, width: 2.6, height: 2.6)), with: .color(.white.opacity(0.5)))
        }
        if locked {
            context.fill(
                Path(roundedRect: CGRect(x: 34, y: 78, width: 64, height: 11), cornerRadius: 3.5), with: .color(.white.opacity(0.28)))
            for (i, color) in [Color.cyan, .orange, .green, .purple, .red].enumerated() {
                context.fill(
                    Path(roundedRect: CGRect(x: 37.5 + CGFloat(i) * 12, y: 80, width: 9, height: 7.5), cornerRadius: 2), with: .color(color))
            }
            TutorialPalette.drawPointer(&context, at: CGPoint(x: 66, y: 81), radius: 4.5)
        } else {
            let dock = Path(roundedRect: CGRect(x: 22, y: 64, width: 88, height: 21), cornerRadius: 7, style: .continuous)
            context.fill(dock, with: .color(Color(white: 0.95).opacity(0.72)))
            context.stroke(dock, with: .color(.white.opacity(0.6)), lineWidth: 0.6)
            for (i, color) in [Color.blue, .green, .orange, .pink, .indigo].enumerated() {
                context.fill(
                    Path(roundedRect: CGRect(x: 27 + CGFloat(i) * 16.5, y: 67.5, width: 12.5, height: 12.5), cornerRadius: 3.2, style: .continuous),
                    with: .color(color))
            }
            TutorialPalette.drawPointer(&context, at: CGPoint(x: 70, y: 76), radius: 5)
        }
    }
}
#endif
