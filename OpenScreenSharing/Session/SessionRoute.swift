import Foundation

nonisolated struct SessionRoute: Codable, Hashable, Identifiable, Sendable {
    var id = UUID()
    var computerID: UUID?
    var name: String
    var host: String
    var port: Int
    var username: String?
    var credentials: SessionCredentials?
    var model: String?
    var remoteProtocol: RemoteProtocol
    var usesHighPerformance: Bool
    var server: ServerAuthentication?

    var address: ComputerAddress {
        ComputerAddress(host: host, port: port)
    }

    var knownPlatform: RemotePlatform? {
        if remoteProtocol == .rdp { return .pc }
        let model = model?.trimmingCharacters(in: .whitespaces) ?? ""
        return usesHighPerformance || model.hasPrefix("Mac") || model.hasPrefix("iMac") ? .mac : nil
    }

    init(computer: Computer) {
        computerID = computer.id
        name = computer.displayName
        host = computer.host
        port = computer.port
        username = computer.username.isEmpty ? nil : computer.username
        model = computer.model.isEmpty ? nil : computer.model
        remoteProtocol = computer.remoteProtocol
        usesHighPerformance = computer.usesHighPerformance
    }

    private enum CodingKeys: String, CodingKey {
        case id, computerID, name, host, port, username, model, remoteProtocol, usesHighPerformance, server
    }
}

enum WindowCredentials {
    private static var credentials: [UUID: SessionCredentials] = [:]

    static func send(_ route: SessionRoute) -> SessionRoute {
        var route = route
        credentials[route.id] = route.credentials
        route.credentials = nil
        return route
    }

    static func receive(_ route: SessionRoute) -> SessionRoute {
        var route = route
        route.credentials = credentials.removeValue(forKey: route.id)
        return route
    }
}
