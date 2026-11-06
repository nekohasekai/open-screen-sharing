import Perception
import SwiftUI
#if os(macOS)
import AppKit
#endif

struct SettingsView: View {
    private static let sourceCode = URL(string: "https://github.com/nekohasekai/open-screen-sharing")!

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    AppSettingsView()
                } label: {
                    Label("App", systemImage: "app")
                }
                #if os(iOS)
                if AppSettings.offersFullScreen {
                    NavigationLink {
                        PictureSettingsView()
                    } label: {
                        Label("Picture", systemImage: "rectangle.inset.filled")
                    }
                }
                #endif
                NavigationLink {
                    KeyboardView()
                } label: {
                    Label("Keyboard", systemImage: "keyboard")
                }
                NavigationLink {
                    QuickInputView()
                } label: {
                    Label("Quick Input", systemImage: "text.insert")
                }
                NavigationLink {
                    ControlSettingsView()
                } label: {
                    Label("Control", systemImage: "slider.horizontal.3")
                }
            }
            Section("About") {
                Link(destination: Self.sourceCode) {
                    Label("Source Code", systemImage: "chevron.left.forwardslash.chevron.right")
                }
                .formRowButtonStyleCompat()
            }
        }
        #if os(macOS)
        .formStyle(.grouped)
        .unboundedFormWidth()
        #endif
        .navigationTitle("Settings")
    }
}

private struct ControlSettingsView: View {
    private let settings = AppSettings.shared
    private let themes = CursorThemeStore.shared

    var body: some View {
        WithPerceptionTracking {
            Form {
                Section("Customization") {
                    NavigationLink {
                        CursorStyleView()
                    } label: {
                        LabeledContentCompat {
                            switch settings.cursorStyle {
                            case .system: Text(CursorStyleView.systemStyleName)
                            case .remote: Text("Remote Style")
                            case .theme(let id): Text(verbatim: themes.theme(id: id)?.name ?? "")
                            }
                        } label: {
                            Label("Cursor Style", systemImage: "cursorarrow")
                        }
                    }
                }
                #if os(iOS)
                Section("Behavior") {
                    Toggle(isOn: Binding(get: { settings.pinchZooms }, set: { settings.pinchZooms = $0 })) {
                        Label("Pinch to Zoom", systemImage: "arrow.up.left.and.down.right.magnifyingglass")
                    }
                }
                #endif
            }
            #if os(macOS)
            .formStyle(.grouped)
            .unboundedFormWidth()
            #endif
            .navigationTitle("Control")
        }
    }
}

#if os(iOS)
private struct PictureSettingsView: View {
    private let settings = AppSettings.shared

    var body: some View {
        WithPerceptionTracking {
            Form {
                Section {
                    Toggle(isOn: Binding(get: { settings.pictureFillsScreen }, set: { settings.pictureFillsScreen = $0 })) {
                        Label("Full Screen", systemImage: "arrow.up.left.and.arrow.down.right")
                    }
                } header: {
                    Text("Behavior")
                } footer: {
                    Text(
                        "Extends the picture to the edges of the screen, where parts of it may be hidden by the rounded corners or the camera. When the pointer moves into a hidden part, that part is also shown nearby."
                    )
                }
            }
            .navigationTitle("Picture")
        }
    }
}
#endif

private struct AppSettingsView: View {
    @State private var language = AppLanguage.selected
    @State private var cacheSize: Int64?
    @State private var alert: AlertState?
    #if os(iOS)
    private let settings = AppSettings.shared
    @State private var replayedTutorials: [SessionTutorial] = []
    #endif

    var body: some View {
        Form {
            Section {
                Picker(selection: $language) {
                    Text(verbatim: AppLanguage.systemDefaultName(in: language)).tag(String?.none)
                    ForEach(AppLanguage.available, id: \.self) { code in
                        Text(verbatim: AppLanguage.name(of: code)).tag(String?.some(code))
                    }
                } label: {
                    Label("Language", systemImage: "globe")
                }
                .onChangeCompat(of: language) { language in
                    AppLanguage.select(language)
                    #if os(macOS)
                    let actions = [
                        AlertState.Action(label: String(localized: "Later"), role: .cancel) {},
                        AlertState.Action(label: String(localized: "Restart"), role: nil, isDefault: true, perform: relaunch),
                    ]
                    #else
                    let actions = [AlertState.Action(label: String(localized: "OK"), role: .cancel, isDefault: true) {}]
                    #endif
                    alert = AlertState(
                        title: String(localized: "Restart Required"),
                        message: String(localized: "The new language takes effect after you restart the app."),
                        actions: actions
                    )
                }
            }
            Section("Cache") {
                LabeledContentCompat {
                    if let cacheSize {
                        Text(cacheSize.formatted(.byteCount(style: .file)))
                    }
                } label: {
                    Label("Cache Size", systemImage: "internaldrive")
                }
                if let cacheSize, cacheSize > 0 {
                    Button(role: .destructive) {
                        Task { await clearCache() }
                    } label: {
                        Label {
                            Text("Clear Cache")
                        } icon: {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                        }
                    }
                    .formRowButtonStyleCompat()
                }
            }
            #if os(iOS)
            WithPerceptionTracking {
                Section("Tips") {
                    Button {
                        setReplayedTutorials(SessionTutorial.allCases.filter(settings.seenTutorials.contains))
                    } label: {
                        Label("Show Tips", systemImage: "lightbulb")
                    }
                    .disabled(settings.seenTutorials.isEmpty)
                }
            }
            #endif
            #if os(macOS)
            UpdatesSection()
            #endif
        }
        #if os(macOS)
        .formStyle(.grouped)
        .unboundedFormWidth()
        #else
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationTitle("App")
        .alert($alert)
        #if os(iOS)
        .fullScreenCover(isPresented: Binding(get: { !replayedTutorials.isEmpty }, set: { if !$0 { replayedTutorials = [] } })) {
            SessionTutorialView(pages: replayedTutorials) {
                setReplayedTutorials([])
            }
            .presentationBackgroundCompat(.clear)
        }
        #endif
        .task {
            cacheSize = await Task.detached(priority: .utility) { AppDirectory.cacheSize() }.value
        }
    }

    #if os(iOS)
    private func setReplayedTutorials(_ tutorials: [SessionTutorial]) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            replayedTutorials = tutorials
        }
    }
    #endif

    private func clearCache() async {
        cacheSize = nil
        do {
            try await Task.detached(priority: .userInitiated) { try AppDirectory.clearCaches() }.value
        } catch {
            alert = AlertState(String(localized: "Couldn’t Clear the Cache"), error: error)
        }
        cacheSize = await Task.detached(priority: .utility) { AppDirectory.cacheSize() }.value
    }

    #if os(macOS)
    private func relaunch() {
        Task {
            do {
                let configuration = NSWorkspace.OpenConfiguration()
                configuration.createsNewApplicationInstance = true
                _ = try await NSWorkspace.shared.openApplication(at: Bundle.main.bundleURL, configuration: configuration)
                NSApp.terminate(nil)
            } catch {
                alert = AlertState(String(localized: "Couldn’t Restart the App"), error: error)
            }
        }
    }
    #endif
}

#if os(macOS)
private struct UpdatesSection: View {
    @SwiftUI.Bindable private var updates = UpdateManager.shared
    @State private var isEditingToken = false

    var body: some View {
        Section("Updates") {
            Toggle(isOn: $updates.checksAutomatically) {
                Label("Check for Updates Automatically", systemImage: "clock.arrow.circlepath")
            }
            Picker(selection: $updates.track) {
                Text("Stable").tag(UpdateTrack.stable)
                Text("Nightly").tag(UpdateTrack.nightly)
            } label: {
                Label("Update Track", systemImage: "arrow.triangle.branch")
            }
            LabeledContentCompat {
                Button("Set") { isEditingToken = true }
                    .sheet(isPresented: $isEditingToken) {
                        GitHubTokenSheet()
                    }
            } label: {
                Label("GitHub Token", systemImage: "key")
            }
            Button {
                Task { await updates.check(manually: true) }
            } label: {
                if updates.isChecking {
                    Label {
                        Text("Checking…")
                    } icon: {
                        ProgressView()
                            .controlSize(.small)
                    }
                } else {
                    Label("Check for Updates…", systemImage: "arrow.triangle.2.circlepath")
                }
            }
            .formRowButtonStyleCompat()
            .disabled(updates.isChecking)
        }
    }
}

private struct GitHubTokenSheet: View {
    private static let newToken = URL(string: "https://github.com/settings/personal-access-tokens/new")!

    private let updates = UpdateManager.shared
    @Environment(\.dismiss) private var dismiss
    @State private var token = UpdateManager.shared.githubToken
    @State private var isUsable = false

    var body: some View {
        Form {
            Section {
                LabeledContentCompat("GitHub Token") {
                    TextField("GitHub Token", text: $token, prompt: Text("Optional"))
                        .multilineTextAlignment(.trailing)
                        .frame(minWidth: 0, idealWidth: 0, maxWidth: .infinity)
                        .autocorrectionDisabled()
                        .onSubmit(save)
                        .onChangeCompat(of: token) { newValue in
                            token = newValue.filter { !$0.isNewline }
                        }
                }
            } footer: {
                HStack {
                    Link("Generate a Token", destination: Self.newToken)
                    Spacer()
                    if isUsable {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .frame(width: 420)
        .fixedSize(horizontal: false, vertical: true)
        .toolbar {
            if !updates.githubToken.isEmpty {
                ToolbarItem(placement: .destructiveAction) {
                    Button("Delete", role: .destructive) {
                        updates.githubToken = ""
                        dismiss()
                    }
                }
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel", role: .cancel) { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save", role: .confirmCompat, action: save)
            }
        }
        .task(id: trimmedToken) {
            isUsable = false
            guard !trimmedToken.isEmpty else { return }
            do {
                try await Task.sleep(for: .milliseconds(500))
                _ = try await UpdateChecker.check(track: updates.track, token: trimmedToken)
                isUsable = true
            } catch {}
        }
    }

    private var trimmedToken: String {
        token.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func save() {
        updates.githubToken = trimmedToken
        dismiss()
    }
}
#endif
