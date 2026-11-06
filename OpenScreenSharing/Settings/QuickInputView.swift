import Perception
import SwiftUI

struct QuickInputView: View {
    private let inputs = QuickInputs.shared
    @State private var editing: Editing?

    private struct Editing: Identifiable {
        let input: QuickInput
        let isNew: Bool

        var id: UUID { input.id }
    }

    var body: some View {
        WithPerceptionTracking {
            Form {
                Section {
                    ForEach(inputs.inputs) { input in
                        HStack {
                            Button {
                                editing = Editing(input: input, isNew: false)
                            } label: {
                                HStack {
                                    Text(verbatim: input.name)
                                        .lineLimit(1)
                                    Spacer()
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            Button {
                                inputs.remove(input.id)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                            }
                            .buttonStyle(.borderless)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel(Text("Delete Quick Input"))
                        }
                    }
                    .onDelete { offsets in
                        for input in offsets.map({ inputs.inputs[$0] }) {
                            inputs.remove(input.id)
                        }
                    }
                    Button {
                        editing = Editing(input: QuickInput(id: UUID(), name: "", steps: []), isNew: true)
                    } label: {
                        Label("Add Quick Input", systemImage: "plus")
                    }
                    .formRowButtonStyleCompat()
                }
            }
            #if os(macOS)
            .formStyle(.grouped)
            .unboundedFormWidth()
            #else
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .navigationTitle("Quick Input")
            .sheet(item: $editing) { editing in
                QuickInputEditor(input: editing.input, isNew: editing.isNew)
            }
        }
    }
}

private struct QuickInputEditor: View {
    let id: UUID
    let isNew: Bool

    private let inputs = QuickInputs.shared
    @State private var name: String
    @State private var steps: [InputStep]
    @State private var recording: UUID?
    @State private var selectedStep: UUID?
    @Environment(\.dismiss) private var dismiss

    private static let keyKinds: [(target: MappingTarget, title: LocalizedStringKey)] = [(.apple, "Apple Key"), (.pc, "PC Key")]

    init(input: QuickInput, isNew: Bool) {
        id = input.id
        self.isNew = isNew
        _name = State(initialValue: input.name)
        _steps = State(initialValue: input.steps.map { step in
            switch step {
            case .text(let text): InputStep(text: text)
            case .key(let combo, let target): InputStep(key: combo, target: target)
            }
        })
    }

    private var isComplete: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && !steps.isEmpty && steps.allSatisfy(\.isComplete)
    }

    var body: some View {
        NavigationStackCompat {
            SheetForm(isNew ? Text("Add Quick Input") : Text("Edit Quick Input")) {
                Section {
                    TextField("Name", text: $name, prompt: Text("Name"))
                        .labelsHidden()
                }
                Section {
                    InputStepsEditor(steps: $steps, keyKinds: Self.keyKinds, recording: $recording, selected: $selectedStep)
                } header: {
                    Text("Sends")
                } footer: {
                    Text("Configured Apple keys and PC keys take effect when connected to an Apple or PC device, respectively.")
                }
            }
            #if os(iOS)
            .environment(\.editMode, .constant(.active))
            #endif
            .inputStepDestination(steps: $steps, selected: $selectedStep, keyKinds: Self.keyKinds, recording: $recording)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: save)
                        .disabled(!isComplete)
                }
            }
        }
        .modifier(ShortcutRecorder(recording: $recording, onPress: record))
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 400)
        #endif
    }

    private func record(_ stepID: UUID, _ press: ShortcutPress) -> Bool {
        switch press {
        case .cancel:
            recording = nil
        case .clear:
            recording = nil
            guard let index = steps.firstIndex(where: { $0.id == stepID }) else { break }
            steps[index].clearKey()
        case .shortcut(let shortcut):
            guard let index = steps.firstIndex(where: { $0.id == stepID }), steps[index].record(shortcut) else { return false }
            recording = nil
        #if os(macOS)
        case .modifierChord:
            return false
        #endif
        }
        return true
    }

    private func save() {
        let input = QuickInput(id: id, name: name.trimmingCharacters(in: .whitespaces), steps: steps.map { step in
            if let target = step.target {
                .key(step.combo, target: target)
            } else {
                .text(step.text)
            }
        })
        if isNew {
            inputs.add(input)
        } else {
            inputs.update(input)
        }
        dismiss()
    }
}
