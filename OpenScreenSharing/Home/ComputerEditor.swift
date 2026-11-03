import SwiftUI

struct ComputerEditor: View {
    let computer: Computer
    var setup: Setup?

    struct Setup {
        var credentials: SessionCredentials
        var server: ServerAuthentication?
        var connect: (SessionRoute) -> Void
    }

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var address: String
    @State private var authenticates: Bool
    @State private var username: String
    @State private var password: String
    @State private var userPassword = ""
    /// Whether a user password is saved, read once on appear; the password itself needs authentication to read.
    @State private var hasSavedUserPassword: Bool?
    @State private var removesUserPassword = false
    @State private var canSaveUserPassword = UserPasswordStore.canSave
    @State private var choice: ServerChoice
    @State private var networkConfiguration: NetworkConfiguration
    @State private var keepsAlive: Bool
    @State private var disablesUDP: Bool
    @State private var problem: Problem?
    @State private var alert: AlertState?
    @FocusState private var focusedField: Field?

    enum Field {
        case name
        case address
        case username
        case password
        case userPassword
    }

    enum Problem {
        case emptyName
        case invalidAddress
    }

    init(computer: Computer, setup: Setup? = nil) {
        self.computer = computer
        self.setup = setup
        _name = State(initialValue: computer.displayName)
        _address = State(initialValue: computer.addressDescription)
        _authenticates = State(initialValue: computer.authenticates)
        _username = State(initialValue: computer.username)
        _password = State(initialValue: computer.password)
        _choice = State(initialValue: ServerChoice(computer))
        _networkConfiguration = State(initialValue: computer.networkConfiguration)
        _keepsAlive = State(initialValue: computer.keepsAlive)
        _disablesUDP = State(initialValue: computer.disablesUDP)
    }

    private var trimmedUsername: String {
        username.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var offersUserPassword: Bool {
        setup == nil && (choice == .rdp || !computer.deviceType.isIOS) && canSaveUserPassword
    }

    private var isComplete: Bool {
        !authenticates || !trimmedUsername.isEmpty
    }

    var body: some View {
        NavigationStackCompat {
            SheetForm(setup == nil ? Text("Edit Connection") : Text("New Connection")) {
                Section {
                    LabeledContentCompat("Name") {
                        TextField("Name", text: $name, prompt: Text(verbatim: ""))
                            .multilineTextAlignment(.trailing)
                            .focused($focusedField, equals: .name)
                            .submitLabel(setup == nil ? .next : .go)
                            .onSubmit {
                                if setup == nil {
                                    focusedField = .address
                                } else {
                                    save()
                                }
                            }
                    }
                    LabeledContentCompat("Server Address") {
                        TextField("Server Address", text: $address, prompt: Text(verbatim: ""))
                            .multilineTextAlignment(.trailing)
                            #if os(iOS)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            #endif
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: .address)
                            .submitLabel(authenticates ? .next : .done)
                            .onSubmit {
                                if authenticates {
                                    focusedField = .username
                                } else {
                                    save()
                                }
                            }
                            .disabled(setup != nil)
                    }
                    Picker("Server Type", selection: $choice) {
                        if setup == nil || computer.remoteProtocol == .vnc {
                            Text(verbatim: "VNC").tag(ServerChoice.vnc)
                            Text(verbatim: "Apple VNC").tag(ServerChoice.appleVNC)
                        }
                        if setup == nil || computer.remoteProtocol == .rdp {
                            Text(verbatim: "RDP").tag(ServerChoice.rdp)
                        }
                    }
                    .pickerStyle(.menu)
                    .disabled(setup != nil && computer.remoteProtocol == .rdp)
                    if choice == .appleVNC {
                        Picker("Network Quality", selection: $networkConfiguration.quality) {
                            Text("Local Network").tag(NetworkQuality.localNetwork)
                            Text("Internet").tag(NetworkQuality.internet)
                        }
                        .pickerStyle(.menu)
                        // The iPad negotiated 60 fps in the mode-5 capture (observed on iPad17,1, iPadOS 27.2 24B5099f).
                        if networkConfiguration.quality == .internet, computer.deviceType == .mac {
                            Picker("Maximum Frame Rate", selection: $networkConfiguration.internetMaximumFrameRate) {
                                ForEach(MaximumFrameRate.allCases) { rate in
                                    Text("\(rate.rawValue) FPS").tag(rate)
                                }
                            }
                            .pickerStyle(.menu)
                        }
                    }
                } footer: {
                    switch problem {
                    case .emptyName: Text("Enter a name.").foregroundStyle(.red)
                    case .invalidAddress: Text("Enter a valid server address.").foregroundStyle(.red)
                    case nil: EmptyView()
                    }
                }
                if setup == nil {
                    Section {
                        Toggle("Enabled", isOn: $authenticates)
                        if authenticates {
                            LabeledContentCompat("User Name") {
                                TextField("User Name", text: $username, prompt: Text("Required"))
                                    .multilineTextAlignment(.trailing)
                                    #if os(iOS)
                                    .textInputAutocapitalization(.never)
                                    #endif
                                    .autocorrectionDisabled()
                                    .focused($focusedField, equals: .username)
                                    .submitLabel(.next)
                                    .onSubmit { focusedField = .password }
                            }
                            LabeledContentCompat("Password") {
                                TextField("Password", text: $password.masked { focusedField == .password }, prompt: Text("Optional"))
                                    .textContentType(.oneTimeCode)
                                    #if os(iOS)
                                    .textInputAutocapitalization(.never)
                                    #endif
                                    .autocorrectionDisabled()
                                    .focused($focusedField, equals: .password)
                                    .multilineTextAlignment(.trailing)
                                    .submitLabel(.done)
                                    .onSubmit(save)
                            }
                        }
                    } header: {
                        Text("Authentication")
                    }
                }
                if offersUserPassword {
                    Section {
                        LabeledContentCompat("User Password") {
                            SecureField(
                                "User Password", text: $userPassword,
                                prompt: Text(hasSavedUserPassword == true && !removesUserPassword ? "Saved" : "Optional"))
                                .textContentType(.oneTimeCode)
                                .focused($focusedField, equals: .userPassword)
                                .multilineTextAlignment(.trailing)
                                .submitLabel(.done)
                                .onSubmit(save)
                        }
                        if hasSavedUserPassword == true, !removesUserPassword, userPassword.isEmpty {
                            Button("Remove Saved Password", role: .destructive) {
                                removesUserPassword = true
                            }
                            .formRowButtonStyleCompat()
                        }
                    } footer: {
                        Text("Type User Password types this into the lock screen or login window. It’s kept in the Keychain on this device.")
                    }
                }
                if choice == .rdp || choice == .appleVNC {
                    Section {
                        if choice == .rdp {
                            Toggle("Disable UDP", isOn: $disablesUDP)
                        }
                        Toggle("Keep Alive", isOn: $keepsAlive)
                    } header: {
                        Text("Connection")
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if setup == nil {
                        Button("Save", role: .confirmCompat, action: save)
                            .disabled(!isComplete)
                    } else {
                        Button("Connect", role: .confirmCompat, action: save)
                            .keyboardShortcut(.defaultAction)
                    }
                }
            }
            .alert($alert)
            .onAppear {
                // Read once: init runs again whenever the presenting view updates.
                guard setup == nil, hasSavedUserPassword == nil else { return }
                hasSavedUserPassword = UserPasswordStore.hasPassword(for: computer.id)
            }
        }
    }

    private func save() {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            problem = .emptyName
            focusedField = .name
            return
        }
        guard let endpoint = ConnectionTarget.endpoint(parsing: Substring(address.trimmingCharacters(in: .whitespacesAndNewlines))) else {
            problem = .invalidAddress
            focusedField = .address
            return
        }
        let address = ComputerAddress(host: endpoint.host, port: endpoint.port ?? choice.remoteProtocol.defaultPort)
        guard isComplete else {
            focusedField = .username
            return
        }
        let computer: Computer
        var serverChanged = false
        do {
            computer = try ComputerStore.shared.save(self.computer) { computer in
                computer.name = name
                if computer.remoteProtocol != choice.remoteProtocol || !computer.address.matches(address) {
                    computer.trustedFingerprint = ""
                    serverChanged = true
                }
                computer.address = address
                computer.authenticates = authenticates
                computer.username = authenticates ? trimmedUsername : ""
                computer.password = authenticates ? password : ""
                computer.networkConfiguration = networkConfiguration
                computer.remoteProtocol = choice.remoteProtocol
                computer.usesHighPerformance = choice.usesHighPerformance
                computer.keepsAlive = keepsAlive
                computer.disablesUDP = disablesUDP
            }
        } catch {
            alert = AlertState(String(localized: "Couldn’t Save “\(name)”"), error: error)
            return
        }
        // A password saved for another address isn't typed into whatever answers at this one.
        if setup == nil, offersUserPassword && !userPassword.isEmpty || removesUserPassword || serverChanged {
            do {
                // Empty after Remove Saved Password or a changed address, which removes it.
                try UserPasswordStore.setPassword(
                    offersUserPassword ? userPassword : "", server: computer.trustedFingerprint, for: computer.id)
            } catch {
                alert = AlertState(String(localized: "Couldn’t Save “\(name)”"), error: error)
                return
            }
        }
        if let setup {
            var route = SessionRoute(computer: computer)
            route.credentials = setup.credentials
            route.server = setup.server
            setup.connect(route)
        }
        dismiss()
    }
}

struct NearbyDetailsView: View {
    let computer: NearbyComputer

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStackCompat {
            SheetForm(Text(verbatim: computer.name)) {
                Section {
                    LabeledContentCompat("Server Address") {
                        if let host = computer.hostName {
                            Text(host)
                        } else {
                            InlineProgress("Looking Up…")
                        }
                    }
                    if let port = computer.port {
                        LabeledContentCompat("Port", value: String(port))
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    CloseButton { dismiss() }
                }
            }
        }
        .phonePresentationDetents([.medium])
    }
}

private enum ServerChoice: Hashable {
    case vnc
    case appleVNC
    case rdp

    init(_ computer: Computer) {
        self = computer.remoteProtocol == .rdp ? .rdp : computer.usesHighPerformance ? .appleVNC : .vnc
    }

    var remoteProtocol: RemoteProtocol {
        self == .rdp ? .rdp : .vnc
    }

    var usesHighPerformance: Bool {
        self == .appleVNC
    }
}
