import Perception
import SwiftUI

struct NewComputer: Hashable {
    var name: String
    var model: String?
    var username = ""
    var password = ""
    var server: ProbedServer

    func signedIn(_ credentials: SessionCredentials, check: LoginCheck) -> SignedInComputer {
        let account = credentials.account
        var saved = Computer(
            name: name, address: server.address, remoteProtocol: server.remoteProtocol, username: account?.username ?? "")
        saved.password = account?.password ?? ""
        saved.authenticates = account != nil
        saved.model = model ?? ""
        saved.trustedFingerprint = check.fingerprint
        saved.usesHighPerformance = check.supportsHighPerformance
        saved.usesNativeTouch = SessionMemory.defaultUsesNativeTouch
        return SignedInComputer(computer: saved, credentials: credentials, server: server.authentication)
    }
}

struct SignedInComputer: Hashable {
    let computer: Computer
    let credentials: SessionCredentials
    let server: ServerAuthentication?
}

struct SetupRequest: Identifiable {
    let id = UUID()
    var link: RemoteLink?
}

struct ConnectionSetupView: View {
    let link: RemoteLink?
    let connect: (SessionRoute) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(NearbyBrowser.self) private var nearby
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
                        credentials: signedIn.credentials,
                        server: signedIn.server,
                        connect: connect
                    )
                )
                .transition(Self.pushTransition)
            } else {
                NavigationStackCompat {
                    ConnectToView(link: link, close: { dismiss() }, signIn: signIn, signedIn: configure)
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
    @State private var alert: AlertState?
    @FocusState private var focusedField: Field?
    @Environment(\.dismiss) private var dismiss

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
        asksForUsername ? !trimmedUsername.isEmpty : !password.isEmpty
    }

    private var credentials: SessionCredentials {
        asksForUsername ? .account(username: trimmedUsername, password: password) : .vncPassword(password)
    }

    var body: some View {
        SheetForm(Text(verbatim: computer.name)) {
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
            if asksForUsername {
                OtherConnectionSection(computerID: nil, signIn: signIn(with:))
                    .disabled(task != nil)
            }
        }
        .toolbar {
            #if os(macOS)
            ToolbarItem(placement: .cancellationAction) {
                Button("Back") { dismiss() }
            }
            #endif
            ToolbarItem(placement: .confirmationAction) {
                Button("Next", action: signIn)
                    .disabled(task != nil || !isValid)
            }
        }
        .alert($alert)
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
        guard isValid else { return }
        attempt(credentials) { failure in
            problem = SetupProblem(failure, host: computer.server.address.host, asksForUsername: asksForUsername)
            focusedField = failure.kind == .authenticationFailed ? .password : nil
        }
    }

    private func signIn(with account: SessionCredentials) {
        attempt(account) { failure in
            alert = AlertState(
                failure.title(computerName: computer.name, remoteProtocol: computer.server.remoteProtocol),
                errorMessage: failure.message)
        }
    }

    private func attempt(_ attempted: SessionCredentials, onFailure: @escaping (SessionFailure) -> Void) {
        guard task == nil else { return }
        problem = nil
        task = Task {
            let result = await ServerProber.checkLogin(computer.server, credentials: attempted)
            guard !Task.isCancelled else { return }
            task = nil
            switch result {
            case .success(let check):
                next(computer.signedIn(attempted, check: check))
            case .failure(let failure) where failure.kind == .unverifiedServer:
                next(computer.signedIn(attempted, check: LoginCheck(supportsHighPerformance: false, fingerprint: "")))
            case .failure(let failure):
                onFailure(failure)
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
