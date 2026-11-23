import Foundation
import Network

nonisolated enum AddressKind: String, Codable, Hashable, Sendable {
    case privateNetwork
    case publicNetwork
    case tailscale

    init(host: String) {
        var trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if trimmed.hasPrefix("["), trimmed.hasSuffix("]") {
            trimmed = String(trimmed.dropFirst().dropLast())
        }
        while trimmed.hasSuffix(".") {
            trimmed.removeLast()
        }
        if trimmed.hasSuffix(".ts.net") || trimmed.hasSuffix(".tailscale.net") {
            self = .tailscale
            return
        }
        let unzoned = trimmed.split(separator: "%", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? trimmed
        if let ipv4 = IPv4Address(unzoned) {
            self = Self.classifyIPv4(Array(ipv4.rawValue))
            return
        }
        if let ipv6 = IPv6Address(unzoned) {
            let bytes = Array(ipv6.rawValue)
            if bytes.count == 16 {
                if bytes[0..<10].allSatisfy({ $0 == 0 }), bytes[10] == 0xff, bytes[11] == 0xff {
                    self = Self.classifyIPv4(Array(bytes[12..<16]))
                    return
                }
                if bytes[0] == 0xfd, bytes[1] == 0x7a, bytes[2] == 0x11, bytes[3] == 0x5c, bytes[4] == 0xa1, bytes[5] == 0xe0 {
                    self = .tailscale
                    return
                }
                if (bytes[0..<15].allSatisfy({ $0 == 0 }) && bytes[15] == 1)
                    || (bytes[0] == 0xfe && (bytes[1] & 0xc0) == 0x80)
                    || (bytes[0] & 0xfe) == 0xfc
                {
                    self = .privateNetwork
                    return
                }
            }
            self = .publicNetwork
            return
        }
        if trimmed == "localhost"
            || trimmed.hasSuffix("._rfb._tcp.local")
            || trimmed.hasSuffix(".local")
            || trimmed.hasSuffix(".home.arpa")
            || trimmed.hasSuffix(".lan")
            || trimmed.hasSuffix(".internal")
            || (!trimmed.isEmpty && !trimmed.contains("."))
        {
            self = .privateNetwork
            return
        }
        self = .publicNetwork
    }

    private static func classifyIPv4(_ bytes: [UInt8]) -> AddressKind {
        guard bytes.count == 4 else { return .publicNetwork }
        if bytes[0] == 100, (bytes[1] & 0xc0) == 64 {
            return .tailscale
        }
        if bytes[0] == 10
            || (bytes[0] == 172 && (bytes[1] & 0xf0) == 16)
            || (bytes[0] == 192 && bytes[1] == 168)
            || bytes[0] == 127
            || (bytes[0] == 169 && bytes[1] == 254)
        {
            return .privateNetwork
        }
        return .publicNetwork
    }
}

nonisolated struct ComputerAddress: Codable, Hashable, Sendable, CustomStringConvertible {
    static let defaultPort = 5900

    var host: String
    var port: Int

    init(host: String, port: Int = ComputerAddress.defaultPort) {
        self.host = host
        self.port = port
    }

    var kind: AddressKind {
        AddressKind(host: host)
    }

    var description: String {
        description(defaultPort: Self.defaultPort)
    }

    func description(defaultPort: Int) -> String {
        if port == defaultPort {
            return host
        }
        return host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)"
    }

    var bonjourServiceName: String? {
        let lowered = host.lowercased()
        for suffix in ["._rfb._tcp.local.", "._rfb._tcp.local"] where lowered.hasSuffix(suffix) {
            return String(host.dropLast(suffix.count))
        }
        return nil
    }

    func matches(_ other: ComputerAddress) -> Bool {
        port == other.port && Self.normalized(host) == Self.normalized(other.host)
    }

    private static func normalized(_ host: String) -> String {
        var value = host.lowercased()
        if value.hasSuffix(".") { value.removeLast() }
        return value
    }

    static func host(parsing text: String) -> String? {
        var host = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if host.hasPrefix("["), host.hasSuffix("]") {
            host = String(host.dropFirst().dropLast())
            return IPv6Address(host) != nil ? host : nil
        }
        if host.contains(":") {
            return IPv6Address(host) != nil ? host : nil
        }
        return isHostName(host) ? host : nil
    }

    private static func isHostName(_ text: String) -> Bool {
        guard !text.isEmpty, text.count <= 253 else { return false }
        var labels = text.split(separator: ".", omittingEmptySubsequences: false)
        if labels.count > 1, labels.last?.isEmpty == true {
            labels.removeLast()
        }
        return labels.allSatisfy { label in
            !label.isEmpty && label.count <= 63 && label.first != "-" && label.last != "-"
                && label.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        }
    }
}

nonisolated struct ConnectionTarget: Hashable, Sendable {
    var host: String
    var port: Int?
    var remoteProtocol: RemoteProtocol?
    var username: String?
    var password: String?
}

nonisolated extension ConnectionTarget {
    init?(parsing text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.contains("://") {
            guard let link = RemoteLink(string: trimmed) else { return nil }
            host = link.address.host
            port = link.address.port
            remoteProtocol = link.remoteProtocol
            username = link.username
            password = link.password
            return
        }
        var rest = Substring(trimmed)
        if let at = rest.lastIndex(of: "@") {
            let name = rest[..<at]
            guard !name.isEmpty else { return nil }
            username = String(name)
            rest = rest[rest.index(after: at)...]
        }
        guard let endpoint = Self.endpoint(parsing: rest) else { return nil }
        host = endpoint.host
        port = endpoint.port
    }

    static func endpoint(parsing text: Substring) -> (host: String, port: Int?)? {
        if text.hasPrefix("[") {
            guard let closing = text.firstIndex(of: "]") else { return nil }
            guard let host = ComputerAddress.host(parsing: String(text[...closing])) else { return nil }
            let suffix = text[text.index(after: closing)...]
            if suffix.isEmpty { return (host, nil) }
            guard suffix.hasPrefix(":"), let port = Int(suffix.dropFirst()), (1...65535).contains(port) else { return nil }
            return (host, port)
        }
        let colons = text.count(where: { $0 == ":" })
        if colons == 1, let colon = text.firstIndex(of: ":") {
            guard let host = ComputerAddress.host(parsing: String(text[..<colon])),
                let port = Int(text[text.index(after: colon)...]), (1...65535).contains(port)
            else { return nil }
            return (host, port)
        }
        guard let host = ComputerAddress.host(parsing: String(text)) else { return nil }
        return (host, nil)
    }
}

nonisolated struct RemoteLink: Hashable, Sendable {
    var address: ComputerAddress
    var remoteProtocol: RemoteProtocol
    var username: String?
    var password: String?

    init?(string: String) {
        guard let separator = string.range(of: "://"),
            let remoteProtocol = RemoteProtocol(rawValue: string[..<separator.lowerBound].lowercased())
        else { return nil }
        if remoteProtocol == .rdp, let attributes = Self.rdpAttributes(string[separator.upperBound...]) {
            guard let full = attributes["full address"], let endpoint = ConnectionTarget.endpoint(parsing: Substring(full)) else { return nil }
            address = ComputerAddress(host: endpoint.host, port: endpoint.port ?? remoteProtocol.defaultPort)
            self.remoteProtocol = remoteProtocol
            if let user = attributes["username"], !user.isEmpty {
                let domain = attributes["domain"] ?? ""
                username = user.contains("\\") || domain.isEmpty ? user : "\(domain)\\\(user)"
            }
            return
        }
        guard let components = URLComponents(string: string) else { return nil }
        let host = components.percentEncodedHost.flatMap { $0.removingPercentEncoding } ?? components.host
        guard let host, !host.isEmpty else { return nil }
        let unbracketed = host.hasPrefix("[") && host.hasSuffix("]") ? String(host.dropFirst().dropLast()) : host
        address = ComputerAddress(host: unbracketed, port: components.port ?? remoteProtocol.defaultPort)
        self.remoteProtocol = remoteProtocol
        username = components.user.flatMap { $0.isEmpty ? nil : $0 }
        password = components.password.flatMap { $0.isEmpty && username == nil ? nil : $0 }
    }

    private static func rdpAttributes(_ query: Substring) -> [String: String]? {
        var attributes: [String: String] = [:]
        for pair in query.split(separator: "&") {
            guard let equals = pair.firstIndex(of: "="), let colon = pair[equals...].firstIndex(of: ":"),
                pair.distance(from: equals, to: colon) == 2,
                let key = pair[..<equals].removingPercentEncoding, let value = pair[pair.index(after: colon)...].removingPercentEncoding
            else { return nil }
            attributes[key.lowercased()] = value
        }
        return attributes["full address"] == nil ? nil : attributes
    }
}
