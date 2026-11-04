import Perception
import SwiftUI

struct CredentialsView: View {
    let prompt: CredentialPrompt
    let computerName: String
    let onSubmit: (SessionCredentials, Bool) -> Void
    let onCancel: () -> Void

    @State private var username: String
    @State private var password = ""
    @State private var remember: Bool
    @State private var task: Task<Void, Never>?
    @State private var alert: AlertState?
    @FocusState private var focusedField: Field?
    @Environment(\.dismiss) private var dismiss

    enum Field {
        case username
        case password
    }

    init(
        prompt: CredentialPrompt, computerName: String, onSubmit: @escaping (SessionCredentials, Bool) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.prompt = prompt
        self.computerName = computerName
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        _username = State(initialValue: prompt.username)
        _remember = State(initialValue: prompt.remembers)
    }

    private var asksForUsername: Bool {
        if case .account = prompt.form { return true }
        return false
    }

    private var allowsRequest: Bool {
        prompt.form == .account(allowsRequest: true)
    }

    private var trimmedUsername: String {
        username.trimmingCharacters(in: .whitespaces)
    }

    private var isValid: Bool {
        asksForUsername ? !trimmedUsername.isEmpty : !password.isEmpty
    }

    var body: some View {
        NavigationStackCompat {
            ScrollViewReader { scroller in
                SheetForm(Text(verbatim: computerName)) {
                    Section {
                        if asksForUsername {
                            TextField("User Name", text: $username)
                                .textContentType(.oneTimeCode)
                                #if os(iOS)
                                .textInputAutocapitalization(.never)
                                #endif
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .username)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .password }
                        }
                        SecureField("Password", text: $password)
                            .textContentType(.oneTimeCode)
                            .focused($focusedField, equals: .password)
                            .submitLabel(.go)
                            .onSubmit(signIn)
                            .id(Field.password)
                    } footer: {
                        if task != nil {
                            InlineProgress("Signing In…")
                        } else {
                            message
                        }
                    }
                    if asksForUsername {
                        Section {
                            Toggle("Remember Password", isOn: $remember)
                        }
                    }
                    if prompt.server.authentication.account {
                        OtherConnectionSection(computerID: prompt.computerID, signIn: signIn(with:))
                    }
                    if allowsRequest {
                        Section {
                            Button("Ask for Permission Instead", action: askForPermission)
                        } footer: {
                            Text("Someone at the Mac needs to accept.")
                        }
                    }
                }
                .disabled(task != nil)
                #if os(macOS)
                .frame(width: 420)
                .fixedSize(horizontal: false, vertical: true)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel", role: .cancel) {
                            onCancel()
                            dismiss()
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Sign In", role: .confirmCompat, action: signIn)
                            .disabled(task != nil || !isValid)
                    }
                }
                .alert($alert)
                .onAppear {
                    let field: Field = asksForUsername && username.isEmpty ? .username : .password
                    if field == .password {
                        scroller.scrollTo(Field.password)
                    }
                    Task { focusedField = field }
                }
                .onDisappear {
                    task?.cancel()
                    task = nil
                }
            }
        }
    }

    @ViewBuilder
    private var message: some View {
        if prompt.failed {
            VStack(alignment: .leading, spacing: 4) {
                Text(asksForUsername ? "The user name or password is incorrect." : "The password is incorrect.")
                    .foregroundStyle(.red)
                if !prompt.errorMessage.isEmpty {
                    Text(prompt.errorMessage)
                        .textSelection(.enabled)
                }
            }
        } else if asksForUsername {
            Text("Enter the user name and password to sign in to “\(computerName)”.")
        } else {
            Text("Enter the password to sign in to “\(computerName)”.")
        }
    }

    private func signIn() {
        guard task == nil, isValid else { return }
        onSubmit(asksForUsername ? .account(username: trimmedUsername, password: password) : .vncPassword(password), remember)
        dismiss()
    }

    private func signIn(with account: SessionCredentials) {
        guard task == nil else { return }
        task = Task {
            let result = await ServerProber.checkLogin(
                prompt.server, credentials: account, allowsUnverifiedLogin: prompt.allowsUnverifiedLogin)
            guard !Task.isCancelled else { return }
            task = nil
            if case .failure(let failure) = result {
                switch failure.kind {
                case .unverifiedServer, .certificateChanged:
                    break
                default:
                    alert = AlertState(
                        failure.title(computerName: computerName, remoteProtocol: prompt.server.remoteProtocol),
                        errorMessage: failure.message)
                    return
                }
            }
            onSubmit(account, remember)
            dismiss()
        }
    }

    private func askForPermission() {
        onSubmit(.askForPermission(username: trimmedUsername), remember)
        dismiss()
    }
}

struct OtherConnectionSection: View {
    let computerID: UUID?
    let signIn: (SessionCredentials) -> Void

    private let store = ComputerStore.shared

    private var computers: [Computer] {
        let saved = store.computers.filter { $0.authenticates && !$0.username.isEmpty && !$0.password.isEmpty }
        var seen = Set(saved.filter { $0.id == computerID }.map(\.savedAccount))
        return saved.filter { seen.insert($0.savedAccount).inserted }
    }

    var body: some View {
        WithPerceptionTracking {
            let computers = computers
            if !computers.isEmpty {
                Section {
                    ForEach(computers) { computer in
                        Button {
                            signIn(computer.savedAccount)
                        } label: {
                            ComputerRow(name: computer.displayName, detail: computer.username, device: computer.device)
                        }
                        #if os(macOS)
                        .buttonStyle(.plain)
                        #endif
                        .foregroundStyle(.primary)
                    }
                } header: {
                    Text("Use User Name and Password from Another Connection")
                        .textCase(nil)
                }
            }
        }
    }
}

extension Computer {
    fileprivate var savedAccount: SessionCredentials {
        .account(username: username, password: password)
    }
}
