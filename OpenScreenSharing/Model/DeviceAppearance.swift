import SwiftUI

nonisolated struct DeviceAppearance: Hashable, Sendable {
    enum Kind: Hashable, Sendable {
        case generic
        case laptop
        case iMac
        case macMini
        case macStudio
        case macPro(generation: Int)
    }

    var kind: Kind
    var color: RGB?

    struct RGB: Hashable, Sendable {
        var red: Double
        var green: Double
        var blue: Double
    }

    init(model: String, enclosureColor: String = "") {
        kind = Self.kind(of: model)
        color = Self.color(named: enclosureColor)
    }

    var systemImage: String {
        switch kind {
        case .generic, .iMac: "desktopcomputer"
        case .laptop: "laptopcomputer"
        case .macMini: "macmini"
        case .macStudio: if #available(iOS 16, *) { "macstudio" } else { "macmini" }
        case .macPro(let generation): generation <= 1 ? "macpro.gen1" : generation == 2 ? "macpro.gen2" : "macpro.gen3"
        }
    }


    private static let appleSiliconKinds: [String: Kind] = {
        var table: [String: Kind] = [:]
        func add(_ kind: Kind, _ identifiers: String...) {
            for identifier in identifiers { table[identifier] = kind }
        }
        add(.macStudio, "13,1", "13,2", "14,13", "14,14", "15,14", "16,9")
        add(.macMini, "14,3", "14,12", "16,10", "16,11")
        add(.macPro(generation: 3), "14,8")
        add(.iMac, "15,4", "15,5", "16,2", "16,3")
        add(
            .laptop, "14,2", "14,5", "14,6", "14,7", "14,9", "14,10", "14,15", "15,3", "15,6", "15,7", "15,8", "15,9", "15,10", "15,11",
            "15,12", "15,13", "16,1", "16,5", "16,6", "16,7", "16,8", "16,12", "16,13", "17,2")
        return table
    }()

    private static func kind(of model: String) -> Kind {
        let model = model.trimmingCharacters(in: .whitespaces)
        if model.hasPrefix("MacBook") { return .laptop }
        if model.hasPrefix("iMac") { return .iMac }
        if model.hasPrefix("Macmini") { return .macMini }
        if model.hasPrefix("MacPro") {
            let major = Int(model.dropFirst("MacPro".count).prefix { $0.isNumber }) ?? 0
            return .macPro(generation: major >= 7 ? 3 : major == 6 ? 2 : 1)
        }
        if model.hasPrefix("Mac"), model.dropFirst(3).first?.isNumber == true {
            return appleSiliconKinds[String(model.dropFirst(3))] ?? .generic
        }
        return .generic
    }


    private static let namedColors: [String: RGB] = [
        "silver": RGB(red: 0.62, green: 0.63, blue: 0.65),
        "spacegray": RGB(red: 0.40, green: 0.41, blue: 0.43),
        "spacegrey": RGB(red: 0.40, green: 0.41, blue: 0.43),
        "spaceblack": RGB(red: 0.22, green: 0.22, blue: 0.23),
        "black": RGB(red: 0.22, green: 0.22, blue: 0.23),
        "midnight": RGB(red: 0.18, green: 0.22, blue: 0.31),
        "starlight": RGB(red: 0.78, green: 0.72, blue: 0.62),
        "gold": RGB(red: 0.82, green: 0.68, blue: 0.48),
        "rosegold": RGB(red: 0.85, green: 0.63, blue: 0.58),
        "skyblue": RGB(red: 0.52, green: 0.67, blue: 0.80),
        "blue": RGB(red: 0.27, green: 0.45, blue: 0.68),
        "green": RGB(red: 0.33, green: 0.56, blue: 0.43),
        "pink": RGB(red: 0.86, green: 0.47, blue: 0.53),
        "purple": RGB(red: 0.52, green: 0.44, blue: 0.70),
        "orange": RGB(red: 0.89, green: 0.50, blue: 0.27),
        "yellow": RGB(red: 0.86, green: 0.69, blue: 0.24),
    ]

    private static func color(named text: String) -> RGB? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        let key = trimmed.lowercased().filter { $0.isLetter }
        if !key.isEmpty, let color = namedColors[key] { return color }
        if trimmed.hasPrefix("#"), trimmed.count == 7, let value = UInt32(trimmed.dropFirst(), radix: 16) {
            return RGB(red: Double(value >> 16 & 0xFF) / 255, green: Double(value >> 8 & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
        }
        let parts = trimmed.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        if parts.count == 3, parts.allSatisfy({ (0...255).contains($0) }) {
            return RGB(red: parts[0] / 255, green: parts[1] / 255, blue: parts[2] / 255)
        }
        return nil
    }
}

struct DeviceIcon: View {
    let device: DeviceAppearance
    let size: CGSize
    var dimmed = false

    private static let extent = CGSize(width: 1.61, height: 1.37)

    var body: some View {
        Image(systemName: device.systemImage)
            .font(.system(size: min(size.width / Self.extent.width, size.height / Self.extent.height), weight: .light))
            .symbolRenderingMode(.hierarchical)
            .foregroundStyle(tint)
            .saturation(dimmed ? 0 : 1)
            .opacity(dimmed ? 0.5 : 1)
            .accessibilityHidden(true)
    }

    private var tint: AnyShapeStyle {
        if let color = device.color {
            AnyShapeStyle(Color(red: color.red, green: color.green, blue: color.blue))
        } else {
            AnyShapeStyle(.secondary)
        }
    }
}
