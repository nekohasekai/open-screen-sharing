import SwiftUI

struct ComputerEditor: View {
    let computer: Computer
    var setup: Setup?

    struct Setup {
        var password: String
        var server: ServerAuthentication?
        var connect: (SessionRoute) -> Void
    }

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var address: String
    @State private var authenticates: Bool
    @State private var username: String
    @State private var password: String
    @State private var choice: ServerChoice
    @State private var networkConfiguration: NetworkConfiguration
    @State private var problem: Problem?
    @State private var alert: AlertState?
    @FocusState private var focusedField: Field?

    enum Field {
        case name
        case address
        case username
        case password
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
        _password = State(initialValue: setup?.password ?? computer.password)
        _choice = State(initialValue: ServerChoice(computer))
        _networkConfiguration = State(initialValue: computer.networkConfiguration)
    }

    private var trimmedUsername: String {
        username.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isComplete: Bool {
        // An account can be configured without a password, as on a Mac; only the
        // user name is required when authentication is enabled.
        !authenticates || !trimmedUsername.isEmpty
    }

    var body: some View {
        NavigationStackCompat {
            Form {
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
            }
            .navigationTitle(setup == nil ? Text("Edit Connection") : Text("New Connection"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #else
            .formStyle(.grouped)
            #endif
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
        do {
            computer = try ComputerStore.shared.save(self.computer) { computer in
                computer.name = name
                if computer.remoteProtocol != choice.remoteProtocol || !computer.address.matches(address) {
                    computer.trustedFingerprint = ""
                }
                computer.address = address
                computer.authenticates = authenticates
                computer.username = authenticates ? trimmedUsername : ""
                computer.password = authenticates ? password : ""
                computer.networkConfiguration = networkConfiguration
                computer.remoteProtocol = choice.remoteProtocol
                computer.usesHighPerformance = choice.usesHighPerformance
            }
        } catch {
            alert = AlertState(String(localized: "Couldn’t Save “\(name)”"), error: error)
            return
        }
        if let setup {
            var route = SessionRoute(computer: computer)
            // An empty password is a valid credential for accounts without one, so
            // it is passed along instead of dropped.
            route.password = password
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
            Form {
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
            .navigationTitle(computer.name)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #else
            .formStyle(.grouped)
            #endif
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
