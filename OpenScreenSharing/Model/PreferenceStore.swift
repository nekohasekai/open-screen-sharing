import Foundation
import GRDB
import os

final class PreferenceStore {
    static let shared = PreferenceStore()

    private nonisolated struct Item: Codable, FetchableRecord, PersistableRecord {
        static let databaseTableName = "preferences"

        var name: String
        var data: Data
    }

    private let database = AppDatabase.shared

    private init() {}

    func value<T: Decodable>(_ type: T.Type, for name: String) -> T? {
        let item: Item?
        do {
            item = try database.read { try Item.fetchOne($0, key: name) }
        } catch {
            StorageNotice.shared.report(String(localized: "Load Failed"), error: error)
            return nil
        }
        guard let item else { return nil }
        do {
            return try JSONDecoder().decode(type, from: item.data)
        } catch {
            StorageNotice.logger.error("remove unreadable preference \(name, privacy: .public): \(error, privacy: .public)")
            remove(name)
            return nil
        }
    }

    @discardableResult
    func set(_ value: some Encodable, for name: String) -> Bool {
        do {
            let data = try JSONEncoder().encode(value)
            try database.write { try Item(name: name, data: data).save($0) }
            return true
        } catch {
            StorageNotice.shared.report(String(localized: "Save Failed"), error: error)
            return false
        }
    }

    func remove(_ name: String) {
        do {
            _ = try database.write { try Item.deleteOne($0, key: name) }
        } catch {
            StorageNotice.shared.report(String(localized: "Save Failed"), error: error)
        }
    }
}
