import Foundation
import Perception
import SwiftUI

@Perceptible
final class ToolbarLayout {
    static let mac = ToolbarLayout(
        platform: .mac, key: "SessionToolbar", defaultBar: [.displays, .audio, .pointerLock, .keyboard, .clipboard])
    static let pc = ToolbarLayout(
        platform: .pc, key: "SessionToolbar.PC", defaultBar: [.displays, .audio, .pointerLock, .keyboard, .clipboard])

    static func layout(for controller: SessionController) -> ToolbarLayout {
        switch controller.info.platform {
        case .mac: mac
        case .pc: pc
        }
    }

    static let maximumSlots = 8

    let defaultBar: [SessionCommand]
    private let platform: RemotePlatform
    private let key: String

    private(set) var bar: [SessionCommand]
    private(set) var isCustomized: Bool
    private let store = PreferenceStore.shared

    private init(platform: RemotePlatform, key: String, defaultBar: [SessionCommand]) {
        self.platform = platform
        self.key = key
        let defaultBar = defaultBar.filter { $0.applies(to: platform) }
        self.defaultBar = defaultBar
        if let stored = store.value([String].self, for: key) {
            bar = stored.compactMap(SessionCommand.init(rawValue:))
            isCustomized = true
        } else {
            bar = defaultBar
            isCustomized = false
        }
    }

    func otherCommands(in category: SessionCommandCategory) -> [SessionCommand] {
        category.commands.filter { !bar.contains($0) && $0.applies(to: platform) }
    }

    var isDefault: Bool { bar == defaultBar }

    func move(fromOffsets source: IndexSet, toOffset destination: Int) {
        bar.move(fromOffsets: source, toOffset: destination)
        save()
    }

    func move(_ command: SessionCommand, by offset: Int) {
        guard let index = bar.firstIndex(of: command) else { return }
        let target = index + offset
        guard bar.indices.contains(target) else { return }
        bar.swapAt(index, target)
        save()
    }

    func remove(atOffsets offsets: IndexSet) {
        bar.remove(atOffsets: offsets)
        save()
    }

    func remove(_ command: SessionCommand) {
        bar.removeAll { $0 == command }
        save()
    }

    func add(_ command: SessionCommand) {
        guard !bar.contains(command) else { return }
        bar.append(command)
        save()
    }

    func reset() {
        bar = defaultBar
        isCustomized = false
        store.remove(key)
    }

    func plan(for controller: SessionController, slots: Int?) -> ToolbarPlan {
        let offered = bar.filter { $0.isOffered(by: controller) && $0.isEnabled(in: controller) }
        let count = min(max(slots ?? offered.count, 0), offered.count)
        return ToolbarPlan(inBar: Array(offered.prefix(count)), overflow: Array(offered.dropFirst(count)))
    }

    private func save() {
        isCustomized = true
        store.set(bar.map(\.rawValue), for: key)
    }

    static func capsuleSlots(width: CGFloat, itemWidth: CGFloat) -> Int {
        let fixed = 2 * itemWidth + 2 * capsuleGroupSpacing + 2 * capsuleGroupPadding + 2 * capsuleMargin
        return slots(for: width - fixed, itemWidth: itemWidth)
    }

    static func tabBarSlots(width: CGFloat, itemWidth: CGFloat) -> Int {
        capsuleSlots(width: width - tabStripMinimumWidth, itemWidth: itemWidth)
    }

    static func floatingSlots(length: CGFloat, itemWidth: CGFloat, closes: Bool) -> Int {
        let fixed = itemWidth + (closes ? itemWidth + capsuleGroupSpacing : 0) + 2 * capsuleGroupPadding + capsuleGroupSpacing
        return slots(for: length - fixed, itemWidth: itemWidth)
    }

    private static func slots(for width: CGFloat, itemWidth: CGFloat) -> Int {
        guard width > 0, itemWidth > 0 else { return 0 }
        return min(maximumSlots, Int((width / itemWidth).rounded(.down)))
    }

    static let itemSize: CGFloat = 44
    static let stashVisibleThickness: CGFloat = 20
    static let stashCornerRadius: CGFloat = 10
    static let stashHitThickness: CGFloat = 28
    static let capsuleGroupSpacing: CGFloat = 8
    static let capsuleGroupPadding: CGFloat = 4
    static let capsuleMargin: CGFloat = 16
    static let floatingMargin: CGFloat = 8
    static let dockClearance: CGFloat = 4
    static let dockMargin: CGFloat = 16
    static let tabStripMinimumWidth: CGFloat = 280
}

struct ToolbarPlan: Equatable {
    var inBar: [SessionCommand] = []
    var overflow: [SessionCommand] = []
}

enum FloatingToolbarEdge: String, Codable, Sendable {
    case left
    case right
    case top
    case bottom

    var stashChevronSymbol: String {
        switch self {
        case .left: "chevron.compact.right"
        case .right: "chevron.compact.left"
        case .top: "chevron.compact.down"
        case .bottom: "chevron.compact.up"
        }
    }
}

struct FloatingToolbarAnchor: Codable, Equatable, Sendable {
    var edge: FloatingToolbarEdge = .right
    var location: Double = 0.2

    func point(in size: CGSize, ballSize: CGFloat, obstructions: ScreenObstructions) -> CGPoint {
        let geometry = Self.edgeGeometry(for: edge, in: size, ballSize: ballSize, obstructions: obstructions)
        let t = CGFloat(min(max(location, 0), 1))
        let raw = geometry.start + t * (geometry.end - geometry.start)
        let along = Self.clampToSegments(raw, geometry.segments, fallback: (geometry.start + geometry.end) / 2)
        return switch edge {
        case .left, .right: CGPoint(x: geometry.cross, y: along)
        case .top, .bottom: CGPoint(x: along, y: geometry.cross)
        }
    }

    func stashedAlong(
        from ballCenter: CGPoint, in size: CGSize, length: CGFloat, thickness: CGFloat, obstructions: ScreenObstructions
    ) -> CGFloat? {
        let half = length / 2
        let clearance = ToolbarLayout.dockClearance
        let margin = ToolbarLayout.floatingMargin
        let corners = Self.effectiveCorners(in: size, obstructions: obstructions)
        let rawStart: CGFloat
        let rawEnd: CGFloat
        let input: CGFloat
        switch edge {
        case .left:
            rawStart = max(margin + half, corners.topLeft > 0 ? corners.topLeft + half : 0)
            rawEnd = size.height - max(margin + half, corners.bottomLeft > 0 ? corners.bottomLeft + half : 0)
            input = ballCenter.y
        case .right:
            rawStart = max(margin + half, corners.topRight > 0 ? corners.topRight + half : 0)
            rawEnd = size.height - max(margin + half, corners.bottomRight > 0 ? corners.bottomRight + half : 0)
            input = ballCenter.y
        case .top:
            rawStart = max(margin + half, corners.topLeft > 0 ? corners.topLeft + half : 0)
            rawEnd = size.width - max(margin + half, corners.topRight > 0 ? corners.topRight + half : 0)
            input = ballCenter.x
        case .bottom:
            rawStart = max(margin + half, corners.bottomLeft > 0 ? corners.bottomLeft + half : 0)
            rawEnd = size.width - max(margin + half, corners.bottomRight > 0 ? corners.bottomRight + half : 0)
            input = ballCenter.x
        }
        guard rawStart <= rawEnd else { return nil }
        var segments: [(CGFloat, CGFloat)] = [(rawStart, rawEnd)]
        let reach = thickness + clearance
        for occlusion in obstructions.occlusions {
            switch edge {
            case .left where occlusion.minX < reach:
                segments = Self.subtractInterval(occlusion.minY - clearance - half, occlusion.maxY + clearance + half, from: segments)
            case .right where occlusion.maxX > size.width - reach:
                segments = Self.subtractInterval(occlusion.minY - clearance - half, occlusion.maxY + clearance + half, from: segments)
            case .top where occlusion.minY < reach:
                segments = Self.subtractInterval(occlusion.minX - clearance - half, occlusion.maxX + clearance + half, from: segments)
            case .bottom where occlusion.maxY > size.height - reach:
                segments = Self.subtractInterval(occlusion.minX - clearance - half, occlusion.maxX + clearance + half, from: segments)
            default:
                break
            }
        }
        guard !segments.isEmpty else { return nil }
        return Self.clampToSegments(input, segments, fallback: (rawStart + rawEnd) / 2)
    }

    static func clamp(_ value: CGFloat, _ low: CGFloat, _ high: CGFloat) -> CGFloat {
        low > high ? (low + high) / 2 : min(max(value, low), high)
    }

    static func cornerSpanInset(radius: CGFloat, crossOffset: CGFloat, ballRadius: CGFloat, clearance: CGFloat) -> CGFloat {
        let base = ToolbarLayout.floatingMargin + ballRadius
        guard radius > crossOffset else { return base }
        let dx = radius - crossOffset
        let limit = radius - ballRadius - clearance
        guard limit > dx else { return max(base, radius) }
        return max(base, radius - (limit * limit - dx * dx).squareRoot())
    }

    static func effectiveCorners(in size: CGSize, obstructions: ScreenObstructions) -> (
        topLeft: CGFloat, topRight: CGFloat, bottomLeft: CGFloat, bottomRight: CGFloat
    ) {
        let bottomClipped = obstructions.areaHeight > 0 && size.height < obstructions.areaHeight - 1
        return (
            obstructions.topLeft,
            obstructions.topRight,
            bottomClipped ? 0 : obstructions.bottomLeft,
            bottomClipped ? 0 : obstructions.bottomRight
        )
    }

    private static func subtractInterval(
        _ cutLow: CGFloat, _ cutHigh: CGFloat, from segments: [(CGFloat, CGFloat)]
    ) -> [(CGFloat, CGFloat)] {
        guard cutLow < cutHigh else { return segments }
        var result: [(CGFloat, CGFloat)] = []
        for (low, high) in segments {
            if cutHigh <= low || cutLow >= high {
                result.append((low, high))
            } else {
                if cutLow > low {
                    result.append((low, cutLow))
                }
                if cutHigh < high {
                    result.append((cutHigh, high))
                }
            }
        }
        return result
    }

    private static func clampToSegments(_ value: CGFloat, _ segments: [(CGFloat, CGFloat)], fallback: CGFloat) -> CGFloat {
        guard !segments.isEmpty else { return fallback }
        for (low, high) in segments where value >= low && value <= high {
            return value
        }
        var best = segments[0].0
        var bestDistance = abs(value - best)
        for (low, high) in segments {
            for candidate in [low, high] {
                let distance = abs(value - candidate)
                if distance < bestDistance {
                    bestDistance = distance
                    best = candidate
                }
            }
        }
        return best
    }

    private static func edgeGeometry(
        for edge: FloatingToolbarEdge, in size: CGSize, ballSize: CGFloat, obstructions: ScreenObstructions
    ) -> (cross: CGFloat, start: CGFloat, end: CGFloat, segments: [(CGFloat, CGFloat)]) {
        let half = ballSize / 2
        let clearance = ToolbarLayout.dockClearance
        let corners = effectiveCorners(in: size, obstructions: obstructions)
        let cross: CGFloat
        let rawStart: CGFloat
        let rawEnd: CGFloat
        switch edge {
        case .left:
            cross = min(ToolbarLayout.floatingMargin + half, size.width / 2)
            rawStart = cornerSpanInset(radius: corners.topLeft, crossOffset: cross, ballRadius: half, clearance: clearance)
            rawEnd = size.height - cornerSpanInset(radius: corners.bottomLeft, crossOffset: cross, ballRadius: half, clearance: clearance)
        case .right:
            cross = max(size.width - ToolbarLayout.floatingMargin - half, size.width / 2)
            let offset = size.width - cross
            rawStart = cornerSpanInset(radius: corners.topRight, crossOffset: offset, ballRadius: half, clearance: clearance)
            rawEnd = size.height - cornerSpanInset(radius: corners.bottomRight, crossOffset: offset, ballRadius: half, clearance: clearance)
        case .top:
            cross = min(ToolbarLayout.floatingMargin + half, size.height / 2)
            rawStart = cornerSpanInset(radius: corners.topLeft, crossOffset: cross, ballRadius: half, clearance: clearance)
            rawEnd = size.width - cornerSpanInset(radius: corners.topRight, crossOffset: cross, ballRadius: half, clearance: clearance)
        case .bottom:
            cross = max(size.height - ToolbarLayout.floatingMargin - half, size.height / 2)
            let offset = size.height - cross
            rawStart = cornerSpanInset(radius: corners.bottomLeft, crossOffset: offset, ballRadius: half, clearance: clearance)
            rawEnd = size.width - cornerSpanInset(radius: corners.bottomRight, crossOffset: offset, ballRadius: half, clearance: clearance)
        }
        let start = rawStart <= rawEnd ? rawStart : (rawStart + rawEnd) / 2
        let end = rawStart <= rawEnd ? rawEnd : start
        var segments: [(CGFloat, CGFloat)] = [(start, end)]
        for occlusion in obstructions.occlusions {
            switch edge {
            case .left, .right:
                if occlusion.maxX + clearance - 0.01 > cross - half, occlusion.minX - clearance + 0.01 < cross + half {
                    segments = subtractInterval(occlusion.minY - clearance - half, occlusion.maxY + clearance + half, from: segments)
                }
            case .top, .bottom:
                if occlusion.maxY + clearance - 0.01 > cross - half, occlusion.minY - clearance + 0.01 < cross + half {
                    segments = subtractInterval(occlusion.minX - clearance - half, occlusion.maxX + clearance + half, from: segments)
                }
            }
        }
        return (cross, start, end, segments)
    }

    static func clampBall(_ point: CGPoint, in size: CGSize, ballSize: CGFloat, obstructions: ScreenObstructions) -> CGPoint {
        let half = ballSize / 2
        let clearance = ToolbarLayout.dockClearance
        let minX = min(ToolbarLayout.floatingMargin + half, size.width / 2)
        let maxX = max(size.width - ToolbarLayout.floatingMargin - half, size.width / 2)
        let minY = min(ToolbarLayout.floatingMargin + half, size.height / 2)
        let maxY = max(size.height - ToolbarLayout.floatingMargin - half, size.height / 2)
        var p = CGPoint(x: clamp(point.x, minX, maxX), y: clamp(point.y, minY, maxY))
        let corners = effectiveCorners(in: size, obstructions: obstructions)
        if corners.topLeft > 0, p.x < corners.topLeft, p.y < corners.topLeft {
            if corners.topLeft - p.x >= corners.topLeft - p.y {
                p.y = max(p.y, min(maxY, cornerSpanInset(radius: corners.topLeft, crossOffset: p.x, ballRadius: half, clearance: clearance)))
            } else {
                p.x = max(p.x, min(maxX, cornerSpanInset(radius: corners.topLeft, crossOffset: p.y, ballRadius: half, clearance: clearance)))
            }
        }
        if corners.topRight > 0, size.width - p.x < corners.topRight, p.y < corners.topRight {
            let rx = size.width - p.x
            if corners.topRight - rx >= corners.topRight - p.y {
                p.y = max(p.y, min(maxY, cornerSpanInset(radius: corners.topRight, crossOffset: rx, ballRadius: half, clearance: clearance)))
            } else {
                p.x = min(p.x, max(minX, size.width - cornerSpanInset(radius: corners.topRight, crossOffset: p.y, ballRadius: half, clearance: clearance)))
            }
        }
        if corners.bottomLeft > 0, p.x < corners.bottomLeft, size.height - p.y < corners.bottomLeft {
            let by = size.height - p.y
            if corners.bottomLeft - p.x >= corners.bottomLeft - by {
                p.y = min(p.y, max(minY, size.height - cornerSpanInset(radius: corners.bottomLeft, crossOffset: p.x, ballRadius: half, clearance: clearance)))
            } else {
                p.x = max(p.x, min(maxX, cornerSpanInset(radius: corners.bottomLeft, crossOffset: by, ballRadius: half, clearance: clearance)))
            }
        }
        if corners.bottomRight > 0, size.width - p.x < corners.bottomRight, size.height - p.y < corners.bottomRight {
            let rx = size.width - p.x
            let by = size.height - p.y
            if corners.bottomRight - rx >= corners.bottomRight - by {
                p.y = min(p.y, max(minY, size.height - cornerSpanInset(radius: corners.bottomRight, crossOffset: rx, ballRadius: half, clearance: clearance)))
            } else {
                p.x = min(p.x, max(minX, size.width - cornerSpanInset(radius: corners.bottomRight, crossOffset: by, ballRadius: half, clearance: clearance)))
            }
        }
        for occlusion in obstructions.occlusions {
            let left = occlusion.minX - clearance - half
            let right = occlusion.maxX + clearance + half
            let top = occlusion.minY - clearance - half
            let bottom = occlusion.maxY + clearance + half
            guard p.x > left, p.x < right, p.y > top, p.y < bottom else { continue }
            var escapes: [(CGPoint, CGFloat)] = []
            if left >= minX { escapes.append((CGPoint(x: left, y: p.y), p.x - left)) }
            if right <= maxX { escapes.append((CGPoint(x: right, y: p.y), right - p.x)) }
            if top >= minY { escapes.append((CGPoint(x: p.x, y: top), p.y - top)) }
            if bottom <= maxY { escapes.append((CGPoint(x: p.x, y: bottom), bottom - p.y)) }
            if let nearest = escapes.min(by: { $0.1 < $1.1 }) {
                p = nearest.0
            }
        }
        return p
    }

    static func snap(point: CGPoint, in size: CGSize, ballSize: CGFloat, obstructions: ScreenObstructions) -> FloatingToolbarAnchor {
        let clamped = clampBall(point, in: size, ballSize: ballSize, obstructions: obstructions)
        var bestAnchor = FloatingToolbarAnchor()
        var bestDistance = CGFloat.greatestFiniteMagnitude
        for edge in [FloatingToolbarEdge.left, .right, .top, .bottom] {
            let geometry = edgeGeometry(for: edge, in: size, ballSize: ballSize, obstructions: obstructions)
            let inputAlong = (edge == .left || edge == .right) ? clamped.y : clamped.x
            let snappedAlong = clampToSegments(inputAlong, geometry.segments, fallback: (geometry.start + geometry.end) / 2)
            let candidatePoint =
                (edge == .left || edge == .right)
                ? CGPoint(x: geometry.cross, y: snappedAlong) : CGPoint(x: snappedAlong, y: geometry.cross)
            let distance = hypot(clamped.x - candidatePoint.x, clamped.y - candidatePoint.y)
            if distance < bestDistance {
                bestDistance = distance
                let span = geometry.end - geometry.start
                let location = span > 0 ? Double(clamp((snappedAlong - geometry.start) / span, 0, 1)) : 0.5
                bestAnchor = FloatingToolbarAnchor(edge: edge, location: location)
            }
        }
        return bestAnchor
    }
}

enum FloatingToolbarDirection: Equatable {
    case left
    case right
    case up
    case down

    var axis: Axis {
        switch self {
        case .left, .right: .horizontal
        case .up, .down: .vertical
        }
    }

    var transitionAnchor: UnitPoint {
        switch self {
        case .right: .leading
        case .left: .trailing
        case .down: .top
        case .up: .bottom
        }
    }
}

struct FloatingToolbarPlacement: Equatable {
    let ballCenter: CGPoint
    let direction: FloatingToolbarDirection
    let slots: Int

    init(size: CGSize, anchor: FloatingToolbarAnchor, itemWidth: CGFloat, closes: Bool, obstructions: ScreenObstructions) {
        let center = anchor.point(in: size, ballSize: itemWidth, obstructions: obstructions)
        ballCenter = center
        let half = itemWidth / 2
        let gap = ToolbarLayout.capsuleGroupSpacing
        let margin = ToolbarLayout.floatingMargin
        let clearance = ToolbarLayout.dockClearance
        let corners = FloatingToolbarAnchor.effectiveCorners(in: size, obstructions: obstructions)

        func metrics(_ direction: FloatingToolbarDirection) -> (available: CGFloat, occluded: Bool) {
            switch direction {
            case .right:
                let start = center.x + half + gap
                var maxCenter = size.width - margin - half
                if center.y < corners.topRight {
                    maxCenter = min(
                        maxCenter,
                        size.width - FloatingToolbarAnchor.cornerSpanInset(
                            radius: corners.topRight, crossOffset: center.y, ballRadius: half, clearance: clearance))
                }
                if size.height - center.y < corners.bottomRight {
                    maxCenter = min(
                        maxCenter,
                        size.width - FloatingToolbarAnchor.cornerSpanInset(
                            radius: corners.bottomRight, crossOffset: size.height - center.y, ballRadius: half, clearance: clearance))
                }
                let unoccluded = maxCenter + half
                var limit = unoccluded
                for occlusion in obstructions.occlusions
                where occlusion.maxY + clearance - 0.01 > center.y - half && occlusion.minY - clearance + 0.01 < center.y + half
                    && occlusion.maxX > center.x
                {
                    limit = min(limit, occlusion.minX - clearance)
                }
                return (max(0, limit - start), limit < unoccluded - 0.5)
            case .left:
                let start = center.x - half - gap
                var minCenter = margin + half
                if center.y < corners.topLeft {
                    minCenter = max(
                        minCenter,
                        FloatingToolbarAnchor.cornerSpanInset(
                            radius: corners.topLeft, crossOffset: center.y, ballRadius: half, clearance: clearance))
                }
                if size.height - center.y < corners.bottomLeft {
                    minCenter = max(
                        minCenter,
                        FloatingToolbarAnchor.cornerSpanInset(
                            radius: corners.bottomLeft, crossOffset: size.height - center.y, ballRadius: half, clearance: clearance))
                }
                let unoccluded = minCenter - half
                var limit = unoccluded
                for occlusion in obstructions.occlusions
                where occlusion.maxY + clearance - 0.01 > center.y - half && occlusion.minY - clearance + 0.01 < center.y + half
                    && occlusion.minX < center.x
                {
                    limit = max(limit, occlusion.maxX + clearance)
                }
                return (max(0, start - limit), limit > unoccluded + 0.5)
            case .down:
                let start = center.y + half + gap
                var maxCenter = size.height - margin - half
                if center.x < corners.bottomLeft {
                    maxCenter = min(
                        maxCenter,
                        size.height - FloatingToolbarAnchor.cornerSpanInset(
                            radius: corners.bottomLeft, crossOffset: center.x, ballRadius: half, clearance: clearance))
                }
                if size.width - center.x < corners.bottomRight {
                    maxCenter = min(
                        maxCenter,
                        size.height - FloatingToolbarAnchor.cornerSpanInset(
                            radius: corners.bottomRight, crossOffset: size.width - center.x, ballRadius: half, clearance: clearance))
                }
                let unoccluded = maxCenter + half
                var limit = unoccluded
                for occlusion in obstructions.occlusions
                where occlusion.maxX + clearance - 0.01 > center.x - half && occlusion.minX - clearance + 0.01 < center.x + half
                    && occlusion.maxY > center.y
                {
                    limit = min(limit, occlusion.minY - clearance)
                }
                return (max(0, limit - start), limit < unoccluded - 0.5)
            case .up:
                let start = center.y - half - gap
                var minCenter = margin + half
                if center.x < corners.topLeft {
                    minCenter = max(
                        minCenter,
                        FloatingToolbarAnchor.cornerSpanInset(
                            radius: corners.topLeft, crossOffset: center.x, ballRadius: half, clearance: clearance))
                }
                if size.width - center.x < corners.topRight {
                    minCenter = max(
                        minCenter,
                        FloatingToolbarAnchor.cornerSpanInset(
                            radius: corners.topRight, crossOffset: size.width - center.x, ballRadius: half, clearance: clearance))
                }
                let unoccluded = minCenter - half
                var limit = unoccluded
                for occlusion in obstructions.occlusions
                where occlusion.maxX + clearance - 0.01 > center.x - half && occlusion.minX - clearance + 0.01 < center.x + half
                    && occlusion.minY < center.y
                {
                    limit = max(limit, occlusion.maxY + clearance)
                }
                return (max(0, start - limit), limit > unoccluded + 0.5)
            }
        }

        let primary: FloatingToolbarDirection
        let alongOpposite: FloatingToolbarDirection
        let inwardPerpendicular: FloatingToolbarDirection
        switch anchor.edge {
        case .left:
            primary = center.y <= size.height / 2 ? .down : .up
            alongOpposite = primary == .down ? .up : .down
            inwardPerpendicular = .right
        case .right:
            primary = center.y <= size.height / 2 ? .down : .up
            alongOpposite = primary == .down ? .up : .down
            inwardPerpendicular = .left
        case .top:
            primary = center.x <= size.width / 2 ? .right : .left
            alongOpposite = primary == .right ? .left : .right
            inwardPerpendicular = .down
        case .bottom:
            primary = center.x <= size.width / 2 ? .right : .left
            alongOpposite = primary == .right ? .left : .right
            inwardPerpendicular = .up
        }

        let primaryMetrics = metrics(primary)
        let chosen: FloatingToolbarDirection
        if !primaryMetrics.occluded {
            chosen = primary
        } else {
            let oppositeMetrics = metrics(alongOpposite)
            let perpMetrics = metrics(inwardPerpendicular)
            let bestAlong = oppositeMetrics.available > primaryMetrics.available ? alongOpposite : primary
            let bestAlongSpace = max(oppositeMetrics.available, primaryMetrics.available)
            if ToolbarLayout.floatingSlots(length: bestAlongSpace, itemWidth: itemWidth, closes: closes) > 0 {
                chosen = bestAlong
            } else {
                chosen = perpMetrics.available > bestAlongSpace ? inwardPerpendicular : bestAlong
            }
        }
        direction = chosen
        slots = ToolbarLayout.floatingSlots(length: metrics(chosen).available, itemWidth: itemWidth, closes: closes)
    }
}
