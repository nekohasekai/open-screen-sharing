import SwiftUI

struct CredentialsView: View {
    let prompt: CredentialPrompt
    let computerName: String
    let onSubmit: (SessionCredentials, Bool) -> Void
    let onCancel: () -> Void

    @State private var username: String
    @State private var password = ""
    @State private var remember: Bool
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
        if asksForUsername {
            // A Mac (or RDP) account can be configured without a password, so only
            // the user name is required; the server rejects an empty password when
            // the account actually has one.
            return !trimmedUsername.isEmpty
        } else {
            return !password.isEmpty
        }
    }

    var body: some View {
        NavigationStackCompat {
            ScrollViewReader { scroller in
                Form {
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
                        message
                    }
                    if asksForUsername {
                        Section {
                            Toggle("Remember Password", isOn: $remember)
                        }
                    }
                    if allowsRequest {
                        Section {
                            Button("Ask for Permission Instead", action: askForPermission)
                        } footer: {
                            Text("Someone at the Mac needs to accept.")
                        }
                    }
                }
                .navigationTitle(computerName)
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #else
                .formStyle(.grouped)
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
                            .disabled(!isValid)
                    }
                }
                .onAppear {
                    let field: Field = asksForUsername && username.isEmpty ? .username : .password
                    if field == .password {
                        scroller.scrollTo(Field.password)
                    }
                    Task { focusedField = field }
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
        guard isValid else { return }
        let credentials =
            asksForUsername
            ? SessionCredentials(loginType: .account, username: trimmedUsername, password: password)
            : SessionCredentials(loginType: .vncPassword, username: "", password: password)
        onSubmit(credentials, remember)
        dismiss()
    }

    private func askForPermission() {
        onSubmit(SessionCredentials(loginType: .askForPermission, username: trimmedUsername, password: ""), remember)
        dismiss()
    }
}
