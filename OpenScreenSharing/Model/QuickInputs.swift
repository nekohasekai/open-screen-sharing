import Foundation
import Perception

struct QuickInput: Codable, Hashable, Identifiable {
    enum Step: Codable, Hashable {
        case text(String)
        case key(KeyCombo, target: MappingTarget)
    }

    var id: UUID
    var name: String
    var steps: [Step]

    func steps(for target: MappingTarget) -> [KeyMapping.Step] {
        steps.compactMap { step in
            switch step {
            case .text(let text): .text(text)
            case .key(let combo, let stepTarget): stepTarget == target ? .key(combo) : nil
            }
        }
    }
}

@Perceptible
final class QuickInputs {
    static let shared = QuickInputs()

    private static let key = "quickInputs"

    private(set) var inputs: [QuickInput]
    private let store = PreferenceStore.shared

    init() {
        inputs = store.value([QuickInput].self, for: Self.key) ?? []
    }

    func add(_ input: QuickInput) {
        inputs.append(input)
        save()
    }

    func update(_ input: QuickInput) {
        guard let index = inputs.firstIndex(where: { $0.id == input.id }) else { return }
        inputs[index] = input
        save()
    }

    func remove(_ id: UUID) {
        inputs.removeAll { $0.id == id }
        save()
    }

    private func save() {
        store.set(inputs, for: Self.key)
    }
}
