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

    private static func slots(for width: CGFloat, itemWidth: CGFloat) -> Int {
        guard width > 0, itemWidth > 0 else { return 0 }
        return min(maximumSlots, Int((width / itemWidth).rounded(.down)))
    }

    static let itemSize: CGFloat = 44
    static let capsuleGroupSpacing: CGFloat = 8
    static let capsuleGroupPadding: CGFloat = 4
    static let capsuleMargin: CGFloat = 16
    static let dockClearance: CGFloat = 4
    static let dockMargin: CGFloat = 16
}

struct ToolbarPlan: Equatable {
    var inBar: [SessionCommand] = []
    var overflow: [SessionCommand] = []
}
