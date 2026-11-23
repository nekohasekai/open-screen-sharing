import Perception
import SwiftUI

struct ComputerEditor: View {
    let computer: Computer
    var setup: Setup?

    struct Setup {
        var credentials: SessionCredentials
        var server: ServerAuthentication?
        var connect: (SessionRoute) -> Void
        var close: () -> Void
    }

    @Environment(\.dismiss) private var dismiss
    @Environment(NearbyBrowser.self) private var nearby
    @State private var name: String
    @State private var endpoints: [ComputerEndpoint]
    @State private var authenticates: Bool
    @State private var username: String
    @State private var password: String
    @State private var choice: ServerChoice
    @State private var keepsAlive: Bool
    @State private var disablesUDP: Bool
    @State private var reversesScrolling: Bool
    @State private var problem: Problem?
    @State private var alert: AlertState?
    @FocusState private var focusedField: Field?

    enum Field {
        case name
        case username
        case password
    }

    enum Problem {
        case emptyName
    }

    init(computer: Computer, setup: Setup? = nil) {
        self.computer = computer
        self.setup = setup
        _name = State(initialValue: computer.displayName)
        _endpoints = State(initialValue: computer.endpoints)
        _authenticates = State(initialValue: computer.authenticates)
        _username = State(initialValue: computer.username)
        _password = State(initialValue: computer.password)
        _choice = State(initialValue: ServerChoice(computer))
        _keepsAlive = State(initialValue: computer.keepsAlive)
        _disablesUDP = State(initialValue: computer.disablesUDP)
        _reversesScrolling = State(initialValue: computer.reversesScrolling)
    }

    private var trimmedUsername: String {
        username.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isComplete: Bool {
        !endpoints.isEmpty && (!authenticates || !trimmedUsername.isEmpty)
    }

    private var editorCredentials: SessionCredentials? {
        if let setup { return setup.credentials }
        guard authenticates, !trimmedUsername.isEmpty, !password.isEmpty else { return nil }
        return .account(username: trimmedUsername, password: password)
    }

    private var endpointsSummary: String {
        guard let first = endpoints.first else { return "" }
        if endpoints.count == 1 {
            return first.address.description(defaultPort: choice.remoteProtocol.defaultPort)
        }
        return String(endpoints.count)
    }

    var body: some View {
        if setup == nil {
            NavigationStackCompat {
                form
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel", role: .cancel) { dismiss() }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Save", role: .confirmCompat, action: save)
                                .disabled(!isComplete)
                        }
                    }
            }
            .onAppear { nearby.start() }
            .onDisappear { nearby.stop() }
        } else {
            form
                .toolbar {
                    #if os(macOS)
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Back") { dismiss() }
                    }
                    #endif
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Connect", role: .confirmCompat, action: save)
                            .keyboardShortcut(.defaultAction)
                    }
                }
        }
    }

    private var form: some View {
        SheetForm(setup == nil ? Text("Edit Connection") : Text("New Connection")) {
            Section {
                LabeledContentCompat("Name") {
                    TextField("Name", text: $name, prompt: Text(verbatim: ""))
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: .name)
                        .submitLabel(setup == nil && authenticates ? .next : setup == nil ? .done : .go)
                        .onSubmit {
                            if setup == nil, authenticates {
                                focusedField = .username
                            } else {
                                save()
                            }
                        }
                        .onChangeCompat(of: name) { problem = nil }
                }
                if setup == nil {
                    NavigationLink {
                        AddressesView(
                            endpoints: $endpoints,
                            computerName: name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? computer.displayName : name.trimmingCharacters(in: .whitespacesAndNewlines),
                            remoteProtocol: choice.remoteProtocol,
                            usesHighPerformance: choice.usesHighPerformance,
                            trustedFingerprint: computer.trustedFingerprint,
                            credentials: editorCredentials
                        )
                    } label: {
                        LabeledContentCompat {
                            Text(verbatim: endpointsSummary)
                                .lineLimit(1)
                        } label: {
                            Text("Addresses & Networks")
                        }
                    }
                    Picker("Connection Type", selection: choiceBinding) {
                        Text(verbatim: "VNC").tag(ServerChoice.vnc)
                        Text(verbatim: "Apple VNC").tag(ServerChoice.appleVNC)
                        Text(verbatim: "RDP").tag(ServerChoice.rdp)
                    }
                    .pickerStyle(.menu)
                }
            } footer: {
                switch problem {
                case .emptyName: Text("Enter a name.").foregroundStyle(.red)
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
            if choice != .appleVNC {
                Section {
                    Toggle("Reverse Scroll Direction", isOn: $reversesScrolling)
                } header: {
                    Text("Control")
                }
            }
        }
        .alert($alert)
    }

    private var choiceBinding: Binding<ServerChoice> {
        Binding(
            get: { choice },
            set: { newChoice in
                let oldDefaultPort = choice.remoteProtocol.defaultPort
                let newDefaultPort = newChoice.remoteProtocol.defaultPort
                if oldDefaultPort != newDefaultPort {
                    for index in endpoints.indices where endpoints[index].address.port == oldDefaultPort {
                        endpoints[index].address.port = newDefaultPort
                    }
                }
                choice = newChoice
            }
        )
    }

    private func save() {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            problem = .emptyName
            focusedField = .name
            return
        }
        let activeEndpoints = setup != nil ? self.computer.endpoints : endpoints
        guard !activeEndpoints.isEmpty, isComplete else {
            focusedField = .username
            return
        }
        let saved: Computer
        do {
            saved = try ComputerStore.shared.save(self.computer) { computer in
                let previousEndpoints = computer.endpoints
                computer.name = name
                if setup == nil {
                    let sharesEndpoint = activeEndpoints.contains { candidate in
                        previousEndpoints.contains { $0.address.matches(candidate.address) }
                    }
                    if computer.remoteProtocol != choice.remoteProtocol || !sharesEndpoint {
                        computer.trustedFingerprint = ""
                    }
                }
                computer.endpoints = activeEndpoints
                computer.authenticates = authenticates
                computer.username = authenticates ? trimmedUsername : ""
                computer.password = authenticates ? password : ""
                computer.remoteProtocol = choice.remoteProtocol
                computer.usesHighPerformance = choice.usesHighPerformance
                computer.keepsAlive = keepsAlive
                computer.disablesUDP = disablesUDP
                computer.reversesScrolling = choice != .appleVNC && reversesScrolling
            }
        } catch {
            alert = AlertState(String(localized: "Save Failed"), error: error)
            return
        }
        if let setup {
            var route = SessionRoute(computer: saved)
            route.credentials = setup.credentials
            route.server = setup.server
            setup.connect(route)
            setup.close()
        } else {
            dismiss()
        }
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
