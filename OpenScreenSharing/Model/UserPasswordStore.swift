import Foundation
import LocalAuthentication
import Security
import os

/// Keeps the password typed into a remote computer's lock screen or login window in the Keychain, one item per
/// saved connection. It's separate from the sign-in password, which the database keeps.
///
/// Reading it asks for Face ID, Touch ID or the device passcode. On iOS the item itself requires them, so the
/// password can't be read without the owner even on an unlocked device; on macOS the login keychain keeps it and
/// the app asks before reading. A device without a passcode keeps none. Each password is bound to the server key the
/// computer showed when it was saved.
nonisolated enum UserPasswordStore {
    private static let service = (Bundle.main.bundleIdentifier ?? "io.nekohasekai.screensharing") + ".user-password"

    private static func query(for id: UUID, suffix: String = "") -> [CFString: Any] {
        [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: id.uuidString + suffix]
    }

    /// Whether a password is saved, without asking for authentication.
    static func hasPassword(for id: UUID) -> Bool {
        var query = query(for: id)
        query[kSecReturnAttributes] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        #if os(iOS)
        let context = LAContext()
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext] = context
        #endif
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        switch status {
        case errSecSuccess, errSecInteractionNotAllowed:
            return true
        case errSecItemNotFound:
            return false
        default:
            StorageNotice.logger.error("look up user password: \(KeychainError(status), privacy: .public)")
            return false
        }
    }

    /// Whether this device can keep a password, which needs a passcode: without one, anyone holding the device could
    /// read it.
    static var canSave: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// Asks the owner to authenticate, then reads the password. Returns nil when none is saved, and throws `LAError`
    /// when the owner cancels or fails, or the device has no passcode. Cancelling the task cancels the authentication.
    static func authenticatedPassword(for id: UUID, reason: String) async throws -> SavedUserPassword? {
        // Shared with the cancellation handler only to invalidate it, which LAContext allows from any thread.
        nonisolated(unsafe) let context = LAContext()
        var problem: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &problem) else {
            throw problem ?? LAError(.passcodeNotSet)
        }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, error in
                    if success {
                        continuation.resume()
                    } else {
                        continuation.resume(throwing: error ?? LAError(.authenticationFailed))
                    }
                }
            }
        } onCancel: {
            context.invalidate()
        }
        try Task.checkCancellation()
        var query = query(for: id)
        query[kSecReturnData] = true
        query[kSecReturnAttributes] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        #if os(iOS)
        // Reuses the authentication above instead of asking again; macOS keeps the item in the login keychain, which
        // this context would steer the query away from.
        query[kSecUseAuthenticationContext] = context
        #endif
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else {
            if status == errSecItemNotFound { return nil }
            throw KeychainError(status)
        }
        guard let item = result as? [CFString: Any], let data = item[kSecValueData] as? Data,
            let password = String(data: data, encoding: .utf8), !password.isEmpty
        else { return nil }
        let server = (item[kSecAttrGeneric] as? Data).flatMap { String(data: $0, encoding: .utf8) } ?? ""
        return SavedUserPassword(password: password, server: server)
    }

    /// Saves the password for the computer whose server key has the fingerprint `server`, empty when it showed none.
    static func setPassword(_ password: String, server: String, for id: UUID) throws {
        guard !password.isEmpty else {
            try removePassword(for: id)
            return
        }
        var problem: NSError?
        guard LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &problem) else {
            throw problem ?? LAError(.passcodeNotSet)
        }
        var item = query(for: id)
        item[kSecValueData] = Data(password.utf8)
        item[kSecAttrGeneric] = Data(server.utf8)
        item[kSecAttrLabel] = "Screen Sharing User Password"
        #if os(iOS)
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .userPresence, nil) else {
            throw KeychainError(errSecParam)
        }
        item[kSecAttrAccessControl] = access
        #endif
        var status = SecItemAdd(item as CFDictionary, nil)
        if status == errSecDuplicateItem {
            // Replaced rather than updated: changing a protected item would ask for authentication first. A trial copy
            // comes first, so a password that can't be added doesn't remove the saved one.
            let trialQuery = query(for: id, suffix: trialSuffix)
            try remove(trialQuery)
            let trial = item.merging(trialQuery) { $1 }
            status = SecItemAdd(trial as CFDictionary, nil)
            guard status == errSecSuccess else { throw KeychainError(status) }
            try remove(trialQuery)
            try removePassword(for: id)
            status = SecItemAdd(item as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw KeychainError(status) }
    }

    static func removePassword(for id: UUID) throws {
        try remove(query(for: id))
        try remove(query(for: id, suffix: Self.trialSuffix))
    }

    private static let trialSuffix = ".trial"

    /// Removes the passwords of every connection, for when the saved connections are erased.
    static func removeAll() {
        do {
            try remove([kSecClass: kSecClassGenericPassword, kSecAttrService: service])
        } catch {
            StorageNotice.logger.error("remove user passwords: \(error, privacy: .public)")
        }
    }

    private static func remove(_ query: [CFString: Any]) throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status) }
    }

    /// Whether the error means the device has no passcode, so it can't keep a password.
    static func isPasscodeMissing(_ error: any Error) -> Bool {
        (error as? LAError)?.code == .passcodeNotSet
    }

    /// Whether the error means the owner chose not to authenticate, which needs no alert.
    static func isCancellation(_ error: any Error) -> Bool {
        guard let error = error as? LAError else { return false }
        return [.userCancel, .systemCancel, .appCancel].contains(error.code)
    }
}

nonisolated struct SavedUserPassword {
    let password: String
    /// The fingerprint of the server key it was saved for, empty when the computer showed none.
    let server: String

    func belongs(toServer fingerprint: String) -> Bool {
        server.caseInsensitiveCompare(fingerprint) == .orderedSame
    }
}

nonisolated struct KeychainError: LocalizedError, CustomStringConvertible {
    let status: OSStatus

    init(_ status: OSStatus) {
        self.status = status
    }

    var description: String {
        let message = SecCopyErrorMessageString(status, nil) as String? ?? "unknown error"
        return "\(message) (\(status))"
    }

    var errorDescription: String? { description }
}
