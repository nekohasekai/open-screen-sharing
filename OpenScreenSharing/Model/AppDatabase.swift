import Foundation
import GRDB
import os

enum AppDatabase {
    private static let url: URL = {
        #if os(iOS)
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        #else
        let directory = AppDirectory.group
        #endif
        return directory.appendingPathComponent("Screen Sharing.sqlite")
    }()

    static let shared: DatabaseQueue = Demo.current?.database ?? open()

    private static func open() -> DatabaseQueue {
        do {
            do {
                return try load()
            } catch let error as DatabaseError where error.resultCode == .SQLITE_CORRUPT || error.resultCode == .SQLITE_NOTADB {
                StorageNotice.logger.error("reset \(url.lastPathComponent, privacy: .public): \(error, privacy: .public)")
                for path in [url.path, url.path + "-journal", url.path + "-wal", url.path + "-shm"] where FileManager.default.fileExists(atPath: path) {
                    try FileManager.default.removeItem(atPath: path)
                }
                return try load()
            }
        } catch {
            fatalError("cannot open \(url.lastPathComponent): \(error)")
        }
    }

    private static func load() throws -> DatabaseQueue {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let database = try DatabaseQueue(path: url.path)
        let expected = try DatabaseQueue()
        try migrator.migrate(expected)
        do {
            try migrator.migrate(database)
        } catch {
            StorageNotice.logger.error("migrate \(url.lastPathComponent, privacy: .public): \(error, privacy: .public)")
        }
        if try database.read(schema) != expected.read(schema) {
            StorageNotice.logger.error("erase \(url.lastPathComponent, privacy: .public) saved with a different schema")
            try database.erase()
            try migrator.migrate(database)
        }
        try database.write { db in
            for row in try Row.fetchAll(db, sql: "SELECT * FROM computers") {
                do {
                    _ = try Computer(row: row)
                } catch {
                    let name: String = row["name"] ?? ""
                    StorageNotice.logger.error("remove unreadable computer \(name, privacy: .public): \(error, privacy: .public)")
                    try db.execute(sql: "DELETE FROM computers WHERE id = ?", arguments: [row["id"] as DatabaseValue])
                }
            }
        }
        return database
    }

    private nonisolated static func schema(_ db: Database) throws -> [String] {
        try String.fetchAll(db, sql: "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name != 'grdb_migrations' ORDER BY name")
    }

    static let migrator: DatabaseMigrator = {
        var migrator = DatabaseMigrator()
        let usesNativeTouch = SessionMemory.defaultUsesNativeTouch
        migrator.registerMigration("1.0.0", migrate: createTables)
        migrator.registerMigration("1.0.2") { db in
            try db.alter(table: "computers") { t in
                t.add(column: "locksPointer", .boolean).notNull().defaults(to: false)
                t.add(column: "usesNativeTouch", .boolean).notNull().defaults(to: usesNativeTouch)
                t.add(column: "keepsAlive", .boolean).notNull().defaults(to: false)
                t.add(column: "disablesUDP", .boolean).notNull().defaults(to: false)
                t.add(column: "receivesAudio", .boolean).notNull().defaults(to: true)
                t.add(column: "sendsAudio", .boolean).notNull().defaults(to: false)
            }
            try db.execute(sql: "UPDATE computers SET receivesAudio = remoteAudio = 'fromMac'")
            try db.alter(table: "computers") { t in
                t.drop(column: "supportsHighPerformance")
                t.drop(column: "usesDynamicResolution")
                t.drop(column: "remoteAudio")
            }
        }
        migrator.registerMigration("1.0.5") { db in
            for name in ["keyboardMappings", "pcKeyboardMappings"] {
                guard let data = try Data.fetchOne(db, sql: "SELECT data FROM preferences WHERE name = ?", arguments: [name]),
                    var mappings = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]]
                else { continue }
                for index in mappings.indices {
                    guard let steps = mappings[index]["steps"] as? [[String: Any]] else { continue }
                    mappings[index]["steps"] = steps.map { step -> [String: Any] in
                        guard let payload = step["key"] as? [String: Any], var shortcut = payload["_0"] as? [String: Any],
                            let key = shortcut.removeValue(forKey: "key")
                        else { return step }
                        shortcut["keys"] = [key]
                        return ["key": ["_0": shortcut]]
                    }
                }
                try db.execute(
                    sql: "UPDATE preferences SET data = ? WHERE name = ?",
                    arguments: [JSONSerialization.data(withJSONObject: mappings), name])
            }
        }
        return migrator
    }()

    private nonisolated static func createTables(_ db: Database) throws {
        try db.create(table: "computers", options: .ifNotExists) { t in
            t.primaryKey("id", .blob)
            t.column("name", .text).notNull()
            t.column("host", .text).notNull()
            t.column("port", .integer).notNull()
            t.column("username", .text).notNull()
            t.column("password", .text).notNull()
            t.column("authenticates", .boolean).notNull()
            t.column("model", .text).notNull()
            t.column("enclosureColor", .text).notNull()
            t.column("lastConnected", .datetime)
            t.column("thumbnail", .blob)
            t.column("createdAt", .datetime).notNull()
            t.column("supportsHighPerformance", .boolean).notNull()
            t.column("usesHighPerformance", .boolean).notNull()
            t.column("networkQuality", .text).notNull()
            t.column("internetMaximumFrameRate", .integer).notNull()
            t.column("remoteProtocol", .text).notNull()
            t.column("trustedFingerprint", .text).notNull()
            t.column("quality", .text).notNull()
            t.column("observes", .boolean).notNull()
            t.column("sharesClipboard", .boolean).notNull()
            t.column("usesDynamicResolution", .boolean).notNull()
            t.column("resolutionScale", .double).notNull()
            t.column("displayID", .integer).notNull()
            t.column("usesHDR", .boolean).notNull()
            t.column("remoteAudio", .text).notNull()
            t.column("keyboardSources", .text).notNull()
            t.column("windowWidth", .double).notNull()
            t.column("windowHeight", .double).notNull()
        }
        try db.create(table: "preferences", options: .ifNotExists) { t in
            t.primaryKey("name", .text)
            t.column("data", .blob).notNull()
        }
    }
}
