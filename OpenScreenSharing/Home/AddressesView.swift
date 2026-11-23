import Perception
import SwiftUI

struct AddressesView: View {
    @Binding var endpoints: [ComputerEndpoint]
    let computerName: String
    let remoteProtocol: RemoteProtocol
    let usesHighPerformance: Bool
    let trustedFingerprint: String
    let credentials: SessionCredentials?
    var setupNext: (() -> Void)?

    @Environment(NearbyBrowser.self) private var nearby
    @Environment(\.dismiss) private var dismiss
    @State private var isAdding = false
    @State private var newEndpoint = ComputerEndpoint(
        address: ComputerAddress(host: ""),
        networkConfiguration: .defaultConfiguration(for: .privateNetwork)
    )
    @State private var suggested: [ComputerAddress] = []
    @State private var isSearching = false

    private var availableSuggestions: [ComputerAddress] {
        suggested.filter { candidate in
            !endpoints.contains { $0.address.matches(candidate) }
        }
    }

    var body: some View {
        WithPerceptionTracking {
            SheetForm(Text("Addresses & Networks")) {
                Section {
                    ForEach(endpoints) { endpoint in
                        NavigationLink {
                            AddressEditorView(
                                endpoint: binding(for: endpoint),
                                remoteProtocol: remoteProtocol,
                                usesHighPerformance: usesHighPerformance,
                                isNew: false,
                                onAdd: nil,
                                onDelete: endpoints.count > 1 ? { removeEndpoint(id: endpoint.id) } : nil
                            )
                        } label: {
                            EndpointRow(
                                endpoint: endpoint,
                                remoteProtocol: remoteProtocol,
                                usesHighPerformance: usesHighPerformance
                            )
                        }
                        .contextMenu {
                            if endpoints.count > 1 {
                                Button(role: .destructive) {
                                    removeEndpoint(id: endpoint.id)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                        }
                        #if os(iOS)
                        .swipeActions(edge: .trailing) {
                            if endpoints.count > 1 {
                                Button(role: .destructive) {
                                    removeEndpoint(id: endpoint.id)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                        }
                        #endif
                    }
                    .onMove { source, destination in
                        endpoints.move(fromOffsets: source, toOffset: destination)
                    }
                } header: {
                    HStack {
                        Text("Addresses")
                        Spacer()
                        Button("Add") {
                            newEndpoint = ComputerEndpoint(
                                address: ComputerAddress(host: "", port: remoteProtocol.defaultPort),
                                networkConfiguration: .defaultConfiguration(for: .privateNetwork)
                            )
                            isAdding = true
                        }
                        .font(.subheadline)
                        .textCase(nil)
                    }
                }

                Section {
                    ForEach(availableSuggestions, id: \.self) { candidate in
                        Button {
                            withAnimation {
                                endpoints.append(
                                    ComputerEndpoint(
                                        address: candidate,
                                        networkConfiguration: .defaultConfiguration(for: candidate.kind)
                                    )
                                )
                            }
                        } label: {
                            HStack(spacing: 12) {
                                EndpointRow(
                                    endpoint: ComputerEndpoint(
                                        address: candidate,
                                        networkConfiguration: .defaultConfiguration(for: candidate.kind)
                                    ),
                                    remoteProtocol: remoteProtocol,
                                    usesHighPerformance: usesHighPerformance
                                )
                                Image(systemName: "plus.circle.fill")
                                    .foregroundStyle(.tint)
                            }
                        }
                        #if os(macOS)
                        .buttonStyle(.plain)
                        #endif
                        .foregroundStyle(.primary)
                    }
                    if isSearching {
                        InlineProgress("Looking for Addresses…")
                    } else if availableSuggestions.isEmpty {
                        Text("No Addresses Found")
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Suggested")
                }
            }
            .navigationDestinationCompat(isPresented: $isAdding) {
                AddressEditorView(
                    endpoint: $newEndpoint,
                    remoteProtocol: remoteProtocol,
                    usesHighPerformance: usesHighPerformance,
                    isNew: true,
                    onAdd: { added in
                        if !endpoints.contains(where: { $0.address.matches(added.address) }) {
                            endpoints.append(added)
                        }
                    },
                    onDelete: nil
                )
            }
            #if os(iOS)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    if let setupNext {
                        Button("Next", action: setupNext)
                            .disabled(endpoints.isEmpty)
                    }
                }
            }
            #else
            .toolbar {
                if let setupNext {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Back") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Next", action: setupNext)
                            .disabled(endpoints.isEmpty)
                    }
                } else {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { dismiss() }
                    }
                }
            }
            #endif
            .task {
                await discoverSuggestions()
            }
        }
    }

    private func binding(for endpoint: ComputerEndpoint) -> Binding<ComputerEndpoint> {
        Binding(
            get: { endpoints.first { $0.id == endpoint.id } ?? endpoint },
            set: { updated in
                if let index = endpoints.firstIndex(where: { $0.id == endpoint.id }) {
                    endpoints[index] = updated
                }
            }
        )
    }

    private func removeEndpoint(id: UUID) {
        guard endpoints.count > 1 else { return }
        withAnimation {
            endpoints.removeAll { $0.id == id }
        }
    }

    private func discoverSuggestions() async {
        isSearching = true
        let snapshot = endpoints
        let sshHost =
            (snapshot.first { $0.address.kind == .privateNetwork } ?? snapshot.first { $0.address.kind != .tailscale })?.address.host
        async let tailscaleFound = ServerProber.suggestTailscaleAddresses(
            name: computerName,
            endpoints: snapshot,
            remoteProtocol: remoteProtocol,
            trustedFingerprint: trustedFingerprint,
            sshHost: sshHost,
            credentials: credentials
        )
        async let nearbyFound = discoverNearbyAddresses(existing: snapshot)
        let combined = await tailscaleFound + nearbyFound
        guard !Task.isCancelled else { return }
        var unique: [ComputerAddress] = []
        for address in combined where !unique.contains(where: { $0.matches(address) }) {
            unique.append(address)
        }
        suggested = unique
        isSearching = false
    }

    private func discoverNearbyAddresses(existing: [ComputerEndpoint]) async -> [ComputerAddress] {
        guard !trustedFingerprint.isEmpty else { return [] }
        let candidates = nearby.computers
            .filter { $0.remoteProtocol == remoteProtocol }
            .compactMap(\.address)
            .filter { candidate in !existing.contains { $0.address.matches(candidate) } }
        guard !candidates.isEmpty else { return [] }
        let protocolType = remoteProtocol
        let expectedFingerprint = trustedFingerprint
        return await withTaskGroup(of: ComputerAddress?.self) { group in
            for candidate in candidates {
                group.addTask {
                    guard case .success(let probed) = await ServerProber.probe(
                        host: candidate.host, port: candidate.port, remoteProtocol: protocolType
                    ),
                        !probed.fingerprint.isEmpty,
                        probed.fingerprint.caseInsensitiveCompare(expectedFingerprint) == .orderedSame
                    else { return nil }
                    return probed.address
                }
            }
            var matched: [ComputerAddress] = []
            for await item in group {
                if let item { matched.append(item) }
            }
            return matched
        }
    }
}

private struct EndpointRow: View {
    let endpoint: ComputerEndpoint
    let remoteProtocol: RemoteProtocol
    let usesHighPerformance: Bool

    var body: some View {
        HStack(spacing: 12) {
            EndpointIcon(
                kind: endpoint.address.kind,
                quality: endpoint.networkConfiguration.quality,
                usesHighPerformance: usesHighPerformance
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: endpoint.address.description(defaultPort: remoteProtocol.defaultPort))
                    .lineLimit(1)
                Text(endpoint.networkSummary(usesHighPerformance: usesHighPerformance))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }
}

private struct EndpointIcon: View {
    let kind: AddressKind
    let quality: NetworkQuality
    let usesHighPerformance: Bool

    var body: some View {
        Group {
            if kind == .tailscale {
                Image("TailscaleLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 18, height: 18)
            } else if usesHighPerformance ? quality == .localNetwork : kind == .privateNetwork {
                Image(systemName: "wifi")
            } else {
                Image(systemName: "globe")
            }
        }
        .font(.body)
        .foregroundStyle(.tint)
        .frame(width: 24, height: 24)
        .accessibilityHidden(true)
    }
}

private struct AddressEditorView: View {
    @Binding var endpoint: ComputerEndpoint
    let remoteProtocol: RemoteProtocol
    let usesHighPerformance: Bool
    let isNew: Bool
    let onAdd: ((ComputerEndpoint) -> Void)?
    let onDelete: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var addressText: String
    @State private var customizedNetwork = false
    @State private var invalidAddress = false
    @FocusState private var isAddressFocused: Bool

    init(
        endpoint: Binding<ComputerEndpoint>,
        remoteProtocol: RemoteProtocol,
        usesHighPerformance: Bool,
        isNew: Bool,
        onAdd: ((ComputerEndpoint) -> Void)?,
        onDelete: (() -> Void)?
    ) {
        _endpoint = endpoint
        self.remoteProtocol = remoteProtocol
        self.usesHighPerformance = usesHighPerformance
        self.isNew = isNew
        self.onAdd = onAdd
        self.onDelete = onDelete
        _addressText = State(
            initialValue: isNew ? "" : endpoint.wrappedValue.address.description(defaultPort: remoteProtocol.defaultPort)
        )
    }

    private var trimmedAddress: String {
        addressText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var bandwidthRange: ClosedRange<Double> {
        let range = NetworkConfiguration.bandwidthRangeMbps
        return Double(range.lowerBound)...Double(range.upperBound)
    }

    private var bandwidth: Binding<Double> {
        Binding {
            Double(endpoint.networkConfiguration.bandwidthMbps)
        } set: {
            customizedNetwork = true
            endpoint.networkConfiguration.internetBandwidthMbps = Int($0.rounded())
        }
    }

    var body: some View {
        SheetForm(isNew ? Text("New Address") : Text("Edit Address")) {
            Section {
                LabeledContentCompat("Server Address") {
                    TextField("Server Address", text: $addressText, prompt: Text("Hostname"))
                        .multilineTextAlignment(.trailing)
                        #if os(iOS)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        #endif
                        .autocorrectionDisabled()
                        .focused($isAddressFocused)
                        .submitLabel(.done)
                        .onSubmit(commit)
                        .onChangeCompat(of: addressText) { newValue in
                            invalidAddress = false
                            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard let parsed = ConnectionTarget.endpoint(parsing: Substring(trimmed)) else { return }
                            let updated = ComputerAddress(host: parsed.host, port: parsed.port ?? remoteProtocol.defaultPort)
                            if isNew {
                                endpoint.address = updated
                                if !customizedNetwork {
                                    endpoint.networkConfiguration = .defaultConfiguration(for: updated.kind)
                                }
                            } else {
                                endpoint.address = updated
                            }
                        }
                }
                if usesHighPerformance {
                    Picker(
                        "Network Quality",
                        selection: Binding(
                            get: { endpoint.networkConfiguration.quality },
                            set: {
                                customizedNetwork = true
                                endpoint.networkConfiguration.quality = $0
                            }
                        )
                    ) {
                        Text("Local Network").tag(NetworkQuality.localNetwork)
                        Text("Internet").tag(NetworkQuality.internet)
                    }
                    .pickerStyle(.menu)
                    if endpoint.networkConfiguration.quality == .internet {
                        Toggle(
                            "High Definition",
                            isOn: Binding(
                                get: { endpoint.networkConfiguration.internetHighDefinition },
                                set: {
                                    customizedNetwork = true
                                    endpoint.networkConfiguration.internetHighDefinition = $0
                                }
                            )
                        )
                        if endpoint.networkConfiguration.internetHighDefinition {
                            VStack {
                                LabeledContentCompat("Network Bandwidth") {
                                    Text("\(endpoint.networkConfiguration.bandwidthMbps) Mbps")
                                        .monospacedDigit()
                                }
                                Slider(value: bandwidth, in: bandwidthRange, step: 1) {
                                    Text("Network Bandwidth")
                                } minimumValueLabel: {
                                    Text(verbatim: String(NetworkConfiguration.bandwidthRangeMbps.lowerBound))
                                        .monospacedDigit()
                                } maximumValueLabel: {
                                    Text(verbatim: String(NetworkConfiguration.bandwidthRangeMbps.upperBound))
                                        .monospacedDigit()
                                }
                                #if os(macOS)
                                .labelsHidden()
                                #endif
                                .accessibilityValue(Text("\(endpoint.networkConfiguration.bandwidthMbps) Mbps"))
                            }
                        }
                    }
                }
            } footer: {
                if invalidAddress {
                    Text("Enter a valid server address.")
                        .foregroundStyle(.red)
                }
            }
            if !isNew, let onDelete {
                Section {
                    Button("Delete Address", role: .destructive) {
                        onDelete()
                        dismiss()
                    }
                    .formRowButtonStyleCompat()
                }
            }
        }
        #if os(iOS)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if isNew {
                    Button("Add", role: .confirmCompat, action: commit)
                        .disabled(trimmedAddress.isEmpty)
                }
            }
        }
        #else
        .toolbar {
            if isNew {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add", role: .confirmCompat, action: commit)
                        .disabled(trimmedAddress.isEmpty)
                }
            } else {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: commit)
                }
            }
        }
        #endif
        .onAppear {
            if isNew {
                isAddressFocused = true
            }
        }
    }

    private func commit() {
        guard let parsed = ConnectionTarget.endpoint(parsing: Substring(trimmedAddress)) else {
            invalidAddress = true
            isAddressFocused = true
            return
        }
        let updatedAddress = ComputerAddress(host: parsed.host, port: parsed.port ?? remoteProtocol.defaultPort)
        endpoint.address = updatedAddress
        if isNew {
            if !customizedNetwork {
                endpoint.networkConfiguration = .defaultConfiguration(for: updatedAddress.kind)
            }
            onAdd?(endpoint)
            dismiss()
        } else {
            #if os(macOS)
            dismiss()
            #endif
        }
    }
}

extension ComputerEndpoint {
    func networkSummary(usesHighPerformance: Bool) -> String {
        let kind = address.kind
        guard usesHighPerformance else {
            switch kind {
            case .privateNetwork: return String(localized: "Local Network")
            case .tailscale: return "Tailscale"
            case .publicNetwork: return String(localized: "Internet")
            }
        }
        switch networkConfiguration.quality {
        case .localNetwork:
            let local = String(localized: "Local Network")
            return kind == .tailscale ? "Tailscale · \(local)" : local
        case .internet:
            if networkConfiguration.internetHighDefinition {
                let prefix = kind == .tailscale ? "Tailscale" : String(localized: "Internet")
                let hd = String(localized: "High Definition")
                let rate = String(localized: "\(networkConfiguration.bandwidthMbps) Mbps")
                return "\(prefix) · \(hd) · \(rate)"
            }
            let internet = String(localized: "Internet")
            return kind == .tailscale ? "Tailscale · \(internet)" : internet
        }
    }
}
