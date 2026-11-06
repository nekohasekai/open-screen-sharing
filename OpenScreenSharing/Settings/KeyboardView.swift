import Perception
import SwiftUI

struct KeyboardView: View {
    private let shortcuts = KeyShortcuts.shared
    #if os(iOS)
    private let keyboard = ConnectedKeyboard.shared
    #endif
    @State private var editing: Editing?

    private struct Editing: Identifiable {
        let mapping: KeyMapping
        let target: MappingTarget
        let isNew: Bool

        var id: UUID { mapping.id }
    }

    var body: some View {
        WithPerceptionTracking {
            Group {
                #if os(iOS)
                if keyboard.isConnected {
                    form
                } else {
                    ContentUnavailableViewCompat {
                        Label("No Keyboard", systemImage: "keyboard")
                    } description: {
                        Text("Connect a keyboard to set up shortcuts.")
                    }
                }
                #else
                form
                #endif
            }
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .navigationTitle("Keyboard")
            .sheet(item: $editing) { editing in
                KeyMappingEditor(mapping: editing.mapping, target: editing.target, isNew: editing.isNew)
            }
        }
    }

    private var form: some View {
        Form {
            ForEach(MappingTarget.allCases) { target in
                WithPerceptionTracking {
                    Section(target.title) {
                        ForEach(shortcuts.mappings(for: target)) { mapping in
                            HStack {
                                Button {
                                    editing = Editing(mapping: mapping, target: target, isNew: false)
                                } label: {
                                    HStack {
                                        Text(verbatim: mapping.trigger.displayName)
                                        Image(systemName: "arrow.right")
                                            .foregroundStyle(.secondary)
                                        Text(verbatim: mapping.displayName(for: target))
                                            .lineLimit(1)
                                        Spacer()
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                Button {
                                    shortcuts.removeMapping(mapping.id, from: target)
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                }
                                .buttonStyle(.borderless)
                                .foregroundStyle(.secondary)
                                .accessibilityLabel(Text("Delete Mapping"))
                            }
                        }
                        .onDelete { offsets in
                            for mapping in offsets.map({ shortcuts.mappings(for: target)[$0] }) {
                                shortcuts.removeMapping(mapping.id, from: target)
                            }
                        }
                        Button {
                            editing = Editing(mapping: KeyMapping(id: UUID(), trigger: .unset, steps: []), target: target, isNew: true)
                        } label: {
                            Label("Add Mapping", systemImage: "plus")
                        }
                        .formRowButtonStyleCompat()
                    }
                }
            }
            Section("Shortcuts") {
                ForEach(ShortcutGroup.allCases) { group in
                    NavigationLink {
                        ShortcutGroupView(group: group)
                    } label: {
                        Text(verbatim: group.title)
                    }
                }
            }
        }
        #if os(macOS)
        .formStyle(.grouped)
        .unboundedFormWidth()
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button(role: .destructive) {
                        shortcuts.restoreDefaults()
                    } label: {
                        Label("Restore Defaults", systemImage: "eraser.fill")
                    }
                    .disabled(!shortcuts.isCustomized)
                } label: {
                    Label("Others", systemImage: "ellipsis.circle")
                }
            }
        }
    }
}

private struct ShortcutGroupView: View {
    let group: ShortcutGroup

    private let shortcuts = KeyShortcuts.shared
    @State private var recording: Recording?
    @State private var alert: AlertState?

    var body: some View {
        Form {
            Section {
                ForEach(group.actions) { action in
                    WithPerceptionTracking {
                        row(.action(action), title: action.title, shortcut: shortcuts.shortcut(for: action)?.displayName) {
                            shortcuts.set(nil, for: action)
                        }
                        #if os(macOS)
                        if action == .pointerLock {
                            row(.pointerRelease, title: String(localized: "Release Pointer"), shortcut: EventModifiers(shortcuts.pointerRelease).symbols, clear: nil)
                        }
                        #endif
                    }
                }
            }
        }
        #if os(macOS)
        .formStyle(.grouped)
        .unboundedFormWidth()
        #else
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationTitle(group.title)
        .alert($alert)
        #if os(macOS)
        .modifier(ShortcutRecorder(recording: $recording, modifierChord: .pointerRelease, onPress: record))
        #else
        .modifier(ShortcutRecorder(recording: $recording, onPress: record))
        #endif
    }

    private func row(_ target: Recording, title: String, shortcut: String?, clear: (() -> Void)?) -> some View {
        HStack {
            Text(verbatim: title)
            Spacer()
            if let clear, shortcut != nil, recording != target {
                Button(action: clear) {
                    Image(systemName: "xmark.circle.fill")
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .accessibilityLabel(Text("Clear Shortcut"))
            }
            ShortcutButton(isRecording: recording == target, title: title, shortcut: shortcut) {
                recording = recording == target ? nil : target
            }
        }
    }

    private enum Recording: Hashable {
        case action(ShortcutAction)
        #if os(macOS)
        case pointerRelease
        #endif
    }

    private func record(_ target: Recording, _ press: ShortcutPress) -> Bool {
        switch press {
        case .cancel:
            recording = nil
        case .clear:
            guard case .action(let action) = target else { return false }
            recording = nil
            shortcuts.set(nil, for: action)
        case .shortcut(let shortcut):
            guard case .action(let action) = target, shortcut.isValidTrigger, shortcut.keyboardShortcut != nil else { return false }
            recording = nil
            assign(shortcut, to: action)
        #if os(macOS)
        case .modifierChord(let flags):
            recording = nil
            shortcuts.setPointerRelease(flags)
        #endif
        }
        return true
    }

    private func assign(_ shortcut: KeyShortcut, to action: ShortcutAction) {
        if let other = shortcuts.action(using: shortcut), other != action {
            alert = AlertState(
                title: String(localized: "“\(shortcut.displayName)” Is Already in Use"),
                message: String(localized: "It’s assigned to “\(other.title)”. Replacing it removes that shortcut."),
                actions: [
                    AlertState.Action(label: String(localized: "Cancel"), role: .cancel) {},
                    AlertState.Action(label: String(localized: "Replace"), role: nil, isDefault: true) {
                        shortcuts.set(nil, for: other)
                        shortcuts.set(shortcut, for: action)
                    },
                ]
            )
            return
        }
        let conflicts = MappingTarget.allCases.compactMap { target in
            shortcuts.mapping(triggeredBy: shortcut, for: target).map { (target: target, mapping: $0) }
        }
        if !conflicts.isEmpty {
            let names = conflicts.map { $0.mapping.displayName(for: $0.target) }.formatted(.list(type: .and))
            alert = AlertState(
                title: String(localized: "“\(shortcut.displayName)” Is Already in Use"),
                message: conflicts.count == 1
                    ? String(localized: "It’s mapped to \(names). Replacing it removes that mapping.")
                    : String(localized: "It’s mapped to \(names). Replacing it removes those mappings."),
                actions: [
                    AlertState.Action(label: String(localized: "Cancel"), role: .cancel) {},
                    AlertState.Action(label: String(localized: "Replace"), role: nil, isDefault: true) {
                        for conflict in conflicts {
                            shortcuts.removeMapping(conflict.mapping.id, from: conflict.target)
                        }
                        shortcuts.set(shortcut, for: action)
                    },
                ]
            )
            return
        }
        shortcuts.set(shortcut, for: action)
    }
}

struct ShortcutButton: View {
    let isRecording: Bool
    let title: String
    let shortcut: String?
    var prompt: LocalizedStringKey = "Type Shortcut"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ShortcutButtonLabel(isRecording: isRecording, shortcut: shortcut, prompt: prompt)
        }
        .buttonStyle(.bordered)
        .tint(isRecording ? .accentColor : nil)
        .id(isRecording)
        .accessibilityLabel(Text(verbatim: title))
        .accessibilityValue(isRecording ? Text(prompt) : shortcut.map { Text(verbatim: $0) } ?? Text("None"))
    }
}

struct ShortcutButtonLabel: View {
    let isRecording: Bool
    let shortcut: String?
    let prompt: LocalizedStringKey

    var body: some View {
        if isRecording {
            Text(prompt)
        } else if let shortcut {
            Text(verbatim: shortcut)
        } else {
            Text("None")
                .foregroundStyle(.secondary)
        }
    }
}

private struct KeyMappingEditor: View {
    let id: UUID
    let target: MappingTarget
    let isNew: Bool

    private let shortcuts = KeyShortcuts.shared
    @State private var trigger: KeyShortcut
    @State private var steps: [InputStep]
    @State private var recording: Field?
    @State private var selectedStep: UUID?
    @State private var alert: AlertState?
    @Environment(\.dismiss) private var dismiss

    init(mapping: KeyMapping, target: MappingTarget, isNew: Bool) {
        id = mapping.id
        self.target = target
        self.isNew = isNew
        _trigger = State(initialValue: mapping.trigger)
        _steps = State(initialValue: mapping.steps.map { step in
            switch step {
            case .key(let combo): InputStep(key: combo, target: target)
            case .text(let text): InputStep(text: text)
            }
        })
    }

    private enum Field: Hashable {
        case trigger
        case key(UUID)
    }

    private var mapping: KeyMapping {
        KeyMapping(id: id, trigger: trigger, steps: steps.map { $0.target == nil ? .text($0.text) : .key($0.combo) })
    }

    private var isComplete: Bool {
        !trigger.isUnset && !steps.isEmpty && steps.allSatisfy(\.isComplete)
    }

    var body: some View {
        NavigationStackCompat {
            SheetForm(isNew ? Text("Add Mapping") : Text("Edit Mapping")) {
                Section {
                    HStack {
                        Text("Shortcut")
                        Spacer()
                        ShortcutButton(isRecording: recording == .trigger, title: String(localized: "Shortcut"), shortcut: trigger.isUnset ? nil : trigger.displayName) {
                            recording = recording == .trigger ? nil : .trigger
                        }
                    }
                }
                Section("Sends") {
                    InputStepsEditor(steps: $steps, keyKinds: keyKinds, recording: stepRecording, selected: $selectedStep)
                }
            }
            #if os(iOS)
            .environment(\.editMode, .constant(.active))
            #endif
            .inputStepDestination(steps: $steps, selected: $selectedStep, keyKinds: keyKinds, recording: stepRecording)
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
        .alert($alert)
        .modifier(ShortcutRecorder(recording: $recording, onPress: record))
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 400)
        #endif
    }

    private var keyKinds: [(target: MappingTarget, title: LocalizedStringKey)] {
        [(target, "Key")]
    }

    private var stepRecording: Binding<UUID?> {
        Binding(
            get: {
                guard case .key(let id) = recording else { return nil }
                return id
            },
            set: { recording = $0.map(Field.key) }
        )
    }

    private func record(_ field: Field, _ press: ShortcutPress) -> Bool {
        switch press {
        case .cancel:
            recording = nil
        case .clear:
            recording = nil
            switch field {
            case .trigger:
                trigger = .unset
            case .key(let stepID):
                guard let index = steps.firstIndex(where: { $0.id == stepID }) else { break }
                steps[index].clearKey()
            }
        case .shortcut(let shortcut):
            switch field {
            case .trigger:
                guard shortcut.isValidTrigger else { return false }
                recording = nil
                trigger = shortcut
            case .key(let stepID):
                guard let index = steps.firstIndex(where: { $0.id == stepID }), steps[index].record(shortcut) else { return false }
                recording = nil
            }
        #if os(macOS)
        case .modifierChord:
            return false
        #endif
        }
        return true
    }

    private func save() {
        let shortcut = trigger
        if let other = shortcuts.action(using: shortcut) {
            alert = AlertState(
                title: String(localized: "“\(shortcut.displayName)” Is Already in Use"),
                message: String(localized: "It’s assigned to “\(other.title)”. Replacing it removes that shortcut."),
                actions: [
                    AlertState.Action(label: String(localized: "Cancel"), role: .cancel) {},
                    AlertState.Action(label: String(localized: "Replace"), role: nil, isDefault: true) {
                        shortcuts.set(nil, for: other)
                        commit()
                    },
                ]
            )
        } else if let other = shortcuts.mapping(triggeredBy: shortcut, for: target), other.id != id {
            alert = AlertState(
                title: String(localized: "“\(shortcut.displayName)” Is Already in Use"),
                message: String(localized: "It’s mapped to \(other.displayName(for: target)). Replacing it removes that mapping."),
                actions: [
                    AlertState.Action(label: String(localized: "Cancel"), role: .cancel) {},
                    AlertState.Action(label: String(localized: "Replace"), role: nil, isDefault: true) {
                        shortcuts.removeMapping(other.id, from: target)
                        commit()
                    },
                ]
            )
        } else {
            commit()
        }
    }

    private func commit() {
        if isNew {
            shortcuts.add(mapping, to: target)
        } else {
            shortcuts.update(mapping, in: target)
        }
        dismiss()
    }
}
