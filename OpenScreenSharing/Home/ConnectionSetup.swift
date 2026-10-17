import SwiftUI

struct NewComputer: Hashable {
    var name: String
    var model: String?
    var username = ""
    var password = ""
    var server: ProbedServer

    func signedIn(_ credentials: SessionCredentials, supportsHighPerformance: Bool) -> SignedInComputer {
        let decoder = DecoderCapabilities.current
        var saved = Computer(
            name: name, address: server.address, remoteProtocol: server.remoteProtocol,
            username: credentials.loginType == .account ? credentials.username : "")
        saved.authenticates = credentials.loginType == .account
        saved.model = model ?? ""
        saved.trustedFingerprint = server.fingerprint
        saved.supportsHighPerformance = supportsHighPerformance
        saved.usesHighPerformance = supportsHighPerformance && (decoder.supportsHEVC || decoder.supportsH264)
        return SignedInComputer(computer: saved, password: credentials.password, server: server.authentication)
    }
}

struct SignedInComputer: Hashable {
    let computer: Computer
    let password: String
    let server: ServerAuthentication?
}

struct SetupRequest: Identifiable {
    let id = UUID()
}

struct ConnectionSetupView: View {
    let connect: (SessionRoute) -> Void

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var nearby: NearbyBrowser
    @State private var signingIn: NewComputer?
    @State private var isSigningIn = false
    @State private var signedIn: SignedInComputer?
    @State private var detent = SheetDetent.medium

    var body: some View {
        Group {
            if let signedIn {
                ComputerEditor(
                    computer: signedIn.computer,
                    setup: ComputerEditor.Setup(
                        password: signedIn.password,
                        server: signedIn.server,
                        connect: connect
                    )
                )
                .transition(Self.pushTransition)
            } else {
                NavigationStackCompat {
                    ConnectToView(close: { dismiss() }, signIn: signIn, signedIn: configure)
                        .navigationDestinationCompat(isPresented: $isSigningIn) {
                            if let signingIn {
                                SetupSignInView(computer: signingIn, next: configure)
                            }
                        }
                }
            }
        }
        .phonePresentationDetents([.medium, .large], selection: $detent)
        .onAppear { nearby.start() }
        .onDisappear { nearby.stop() }
    }

    private static var pushTransition: AnyTransition {
        if #available(iOS 16, *) {
            .push(from: .trailing)
        } else {
            .move(edge: .trailing)
        }
    }

    private func signIn(_ computer: NewComputer) {
        signingIn = computer
        isSigningIn = true
    }

    private func configure(_ computer: SignedInComputer) {
        withAnimation {
            detent = .large
            signedIn = computer
        }
    }
}

struct SetupSignInView: View {
    let computer: NewComputer
    let next: (SignedInComputer) -> Void

    @State private var username: String
    @State private var password: String
    @State private var task: Task<Void, Never>?
    @State private var problem: SetupProblem?
    @FocusState private var focusedField: Field?

    enum Field {
        case username
        case password
    }

    init(computer: NewComputer, next: @escaping (SignedInComputer) -> Void) {
        self.computer = computer
        self.next = next
        _username = State(initialValue: computer.username)
        _password = State(initialValue: computer.password)
    }

    private var asksForUsername: Bool {
        computer.server.authentication.account
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

    private var credentials: SessionCredentials {
        asksForUsername
            ? SessionCredentials(loginType: .account, username: trimmedUsername, password: password)
            : SessionCredentials(loginType: .vncPassword, username: "", password: password)
    }

    var body: some View {
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
            } footer: {
                if task != nil {
                    InlineProgress("Signing In…")
                } else if let problem {
                    SetupProblemView(problem: problem)
                } else if asksForUsername {
                    Text("Enter the user name and password to sign in to “\(computer.name)”.")
                } else {
                    Text("Enter the password to sign in to “\(computer.name)”.")
                }
            }
            .disabled(task != nil)
        }
        .navigationTitle(computer.name)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #else
        .formStyle(.grouped)
        #endif
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Next", action: signIn)
                    .disabled(task != nil || !isValid)
            }
        }
        .onAppear {
            focusedField = asksForUsername && trimmedUsername.isEmpty ? .username : .password
        }
        .onDisappear {
            task?.cancel()
            task = nil
        }
        .onChangeCompat(of: username) { problem = nil }
        .onChangeCompat(of: password) { problem = nil }
    }

    private func signIn() {
        guard task == nil, isValid else { return }
        problem = nil
        let attempt = credentials
        task = Task {
            let result = await ServerProber.checkLogin(computer.server, credentials: attempt)
            guard !Task.isCancelled else { return }
            task = nil
            switch result {
            case .success(let supportsHighPerformance):
                next(computer.signedIn(attempt, supportsHighPerformance: supportsHighPerformance))
            case .failure(let failure):
                problem = SetupProblem(failure, host: computer.server.address.host, asksForUsername: asksForUsername)
                focusedField = failure.kind == .authenticationFailed ? .password : nil
            }
        }
    }
}

struct SetupProblem {
    var summary: String
    var error = ""
}

extension SetupProblem {
    init(_ failure: SessionFailure, host: String, asksForUsername: Bool) {
        switch failure.kind {
        case .authenticationFailed:
            summary =
                asksForUsername
                ? String(localized: "The user name or password is incorrect.") : String(localized: "The password is incorrect.")
        case .hostNotFound:
            summary = String(localized: "Can’t find “\(host)”.")
        case .ownScreen:
            summary = String(localized: "You can’t control your own screen.")
        default:
            summary = String(localized: "Can’t connect to “\(host)”.")
        }
        error = failure.message
    }
}

struct SetupProblemView: View {
    let problem: SetupProblem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(problem.summary)
                .foregroundStyle(.red)
            if !problem.error.isEmpty {
                Text(problem.error)
                    .textSelection(.enabled)
            }
        }
    }
}
