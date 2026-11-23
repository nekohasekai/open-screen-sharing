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
        saved.trustedFingerprint = check.fingerprint.isEmpty ? server.fingerprint : check.fingerprint
        saved.usesHighPerformance = check.supportsHighPerformance
        saved.networkConfiguration = .defaultConfiguration(for: server.address.kind)
        saved.usesNativeTouch = SessionMemory.defaultUsesNativeTouch
        return SignedInComputer(computer: saved, credentials: credentials, server: server.authentication)
    }
}

struct SignedInComputer: Hashable {
    var computer: Computer
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
    @State private var endpoints: [ComputerEndpoint] = []
    @State private var isShowingDirectAddresses = false
    @State private var isShowingSignInAddresses = false
    @State private var isConfiguringDirect = false
    @State private var isConfiguringSignIn = false
    @State private var detent = SheetDetent.medium

    var body: some View {
        NavigationStackCompat {
            ConnectToView(
                link: link,
                close: { dismiss() },
                signIn: signIn,
                signedIn: { computer in
                    detent = .large
                    signedIn = computer
                    endpoints = computer.computer.endpoints
                    isShowingDirectAddresses = true
                }
            )
            .navigationDestinationCompat(isPresented: $isSigningIn) {
                if let signingIn {
                    SetupSignInView(computer: signingIn) { computer in
                        detent = .large
                        signedIn = computer
                        endpoints = computer.computer.endpoints
                        isShowingSignInAddresses = true
                    }
                    .navigationDestinationCompat(isPresented: $isShowingSignInAddresses) {
                        addressesDestination(isConfiguring: $isConfiguringSignIn)
                    }
                }
            }
            .navigationDestinationCompat(isPresented: $isShowingDirectAddresses) {
                addressesDestination(isConfiguring: $isConfiguringDirect)
            }
        }
        .phonePresentationDetents([.medium, .large], selection: $detent)
        .onAppear { nearby.start() }
        .onDisappear { nearby.stop() }
    }

    @ViewBuilder
    private func addressesDestination(isConfiguring: Binding<Bool>) -> some View {
        if let signedIn {
            AddressesView(
                endpoints: $endpoints,
                computerName: signedIn.computer.displayName,
                remoteProtocol: signedIn.computer.remoteProtocol,
                usesHighPerformance: signedIn.computer.usesHighPerformance,
                trustedFingerprint: signedIn.computer.trustedFingerprint,
                credentials: signedIn.credentials,
                setupNext: { isConfiguring.wrappedValue = true }
            )
            .navigationDestinationCompat(isPresented: isConfiguring) {
                var configured = signedIn.computer
                let _ = (configured.endpoints = endpoints)
                ComputerEditor(
                    computer: configured,
                    setup: ComputerEditor.Setup(
                        credentials: signedIn.credentials,
                        server: signedIn.server,
                        connect: connect,
                        close: { dismiss() }
                    )
                )
            }
        }
    }

    private func signIn(_ computer: NewComputer) {
        signingIn = computer
        isSigningIn = true
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
            problem = SetupProblem(
                failure, computerName: computer.name, remoteProtocol: computer.server.remoteProtocol, asksForUsername: asksForUsername)
            focusedField = failure.kind == .authenticationFailed ? .password : nil
        }
    }

    private func signIn(with account: SessionCredentials) {
        attempt(account) { failure in
            alert = AlertState(failure, computerName: computer.name, remoteProtocol: computer.server.remoteProtocol)
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
    init(_ failure: SessionFailure, computerName: String, remoteProtocol: RemoteProtocol, asksForUsername: Bool) {
        if failure.kind == .authenticationFailed {
            summary =
                asksForUsername
                ? String(localized: "The user name or password is incorrect.") : String(localized: "The password is incorrect.")
            error = failure.message
        } else if let reason = failure.presentation(computerName: computerName, remoteProtocol: remoteProtocol).reason {
            summary = reason
        } else {
            summary = String(localized: "Connection failed.")
            error = failure.message
        }
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
