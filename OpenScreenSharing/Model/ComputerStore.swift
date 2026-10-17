import Combine
import Foundation
import GRDB

struct StoredComputer {
    var username: String
    var password: String
    var authenticates: Bool
    var memory: SessionMemory
    var networkConfiguration: NetworkConfiguration
    var device: DeviceInfo?
    var usesHighPerformance: Bool
    var deviceType: DeviceType?
    var keyboardSources: [String]
    var trustedFingerprint: String
}

enum PasswordUpdate {
    case keep
    case save(String)
    case delete

    var isPending: Bool {
        if case .keep = self { return false }
        return true
    }
}

struct ConnectionRecord {
    var computerID: UUID?
    var name: String
    var address: ComputerAddress
    var username: String
    var password: PasswordUpdate
    var supportsHighPerformance: Bool
    var remoteProtocol: RemoteProtocol
    var usesHighPerformance: Bool
    var memory: SessionMemory
    var device: DeviceInfo?
}

final class ComputerStore: ObservableObject {
    static let shared = ComputerStore()

    @Published private(set) var computers: [Computer] = []
    private let database = AppDatabase.shared
    private var observation: AnyDatabaseCancellable?

    private init() {
        observation = ValueObservation
            .tracking { try Computer.order(Column("createdAt")).fetchAll($0) }
            .start(in: database, scheduling: .immediate) { error in
                StorageNotice.shared.report("Couldn’t Load Connections", error: error)
            } onChange: { [weak self] computers in
                self?.computers = computers
            }
    }

    func computer(_ id: UUID) -> Computer? {
        do {
            return try database.read { try Computer.fetchOne($0, key: id) }
        } catch {
            StorageNotice.shared.report("Couldn’t Load Connections", error: error)
            return nil
        }
    }

    func stored(_ id: UUID) -> StoredComputer? {
        guard let computer = computer(id) else { return nil }
        return StoredComputer(
            username: computer.username,
            password: computer.authenticates ? computer.password : "",
            authenticates: computer.authenticates,
            memory: computer.memory,
            networkConfiguration: computer.networkConfiguration,
            device: computer.model.isEmpty ? nil : DeviceInfo(model: computer.model, enclosureColor: computer.enclosureColor),
            usesHighPerformance: computer.usesHighPerformance,
            deviceType: computer.usesHighPerformance ? computer.deviceType : nil,
            keyboardSources: computer.keyboardSources,
            trustedFingerprint: computer.trustedFingerprint
        )
    }

    func computer(at address: ComputerAddress, remoteProtocol: RemoteProtocol, username: String?) -> Computer? {
        do {
            return try database.read { try Self.computer(in: $0, at: address, remoteProtocol: remoteProtocol, username: username) }
        } catch {
            StorageNotice.shared.report("Couldn’t Load Connections", error: error)
            return nil
        }
    }

    private static func computer(in db: Database, at address: ComputerAddress, remoteProtocol: RemoteProtocol, username: String?) throws
        -> Computer?
    {
        let computers = try Computer.order(Column("createdAt")).fetchAll(db).filter {
            $0.remoteProtocol == remoteProtocol && $0.address.matches(address)
        }
        guard let username else { return computers.first }
        return computers.first { $0.username == username } ?? computers.first { $0.username.isEmpty }
    }

    func save(_ computer: Computer, modify: (inout Computer) -> Void) throws -> Computer {
        try database.write { db in
            var saved = try Computer.fetchOne(db, key: computer.id) ?? computer
            modify(&saved)
            try saved.save(db)
            return saved
        }
    }

    func delete(_ id: UUID) throws {
        _ = try database.write { try Computer.deleteOne($0, key: id) }
    }

    func record(_ connection: ConnectionRecord) throws -> UUID {
        let username = connection.username.isEmpty ? nil : connection.username
        return try database.write { db in
            var computer: Computer
            if let id = connection.computerID, let saved = try Computer.fetchOne(db, key: id) {
                computer = saved
            } else if let saved = try Self.computer(
                in: db, at: connection.address, remoteProtocol: connection.remoteProtocol, username: username)
            {
                computer = saved
            } else {
                computer = Computer(name: connection.name, address: connection.address, remoteProtocol: connection.remoteProtocol)
                computer.remember(connection.memory)
            }
            if let username, computer.username != username {
                computer.username = username
                computer.password = ""
                computer.authenticates = false
            }
            switch connection.password {
            case .keep:
                break
            case .save(let password):
                computer.password = password
                computer.authenticates = true
            case .delete:
                computer.password = ""
                computer.authenticates = false
            }
            computer.lastConnected = Date()
            computer.supportsHighPerformance = connection.supportsHighPerformance
            computer.remoteProtocol = connection.remoteProtocol
            computer.usesHighPerformance = connection.usesHighPerformance
            if let device = connection.device {
                computer.apply(device)
            }
            try computer.save(db)
            return computer.id
        }
    }

    func remember(_ memory: SessionMemory, for id: UUID) throws {
        try update(id) { $0.remember(memory) }
    }

    func setDevice(_ device: DeviceInfo, for id: UUID) throws {
        try update(id) { $0.apply(device) }
    }

    func setKeyboardSources(_ sources: [String], for id: UUID) throws {
        try update(id) { $0.keyboardSources = sources }
    }

    func setTrustedFingerprint(_ fingerprint: String, for id: UUID) throws {
        try update(id) { $0.trustedFingerprint = fingerprint }
    }

    func setThumbnail(_ thumbnail: Data, for id: UUID) throws {
        try update(id) { $0.thumbnail = thumbnail }
    }

    private func update(_ id: UUID, _ modify: (inout Computer) -> Void) throws {
        return try database.write { db in
            guard var computer = try Computer.fetchOne(db, key: id) else { return }
            try computer.updateChanges(db, modify: modify)
        }
    }
}
