import Foundation
import Security
import sharedKit

/// Keychain-backed `KeyValueStore` for sensitive authentication tokens.
///
/// Uses `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` so tokens remain available for
/// background sync and audio tasks after first unlock while never leaving the device via
/// iCloud backup or device migration.
final class KeychainSecureStore: NSObject, KeyValueStore {
    private let service: String
    private let lock = NSLock()

    init(service: String = "pw.kmr.sonnet.session") {
        self.service = service
        super.init()
    }

    func getString(key: String) -> String? {
        lock.lock()
        defer { lock.unlock() }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func setString(key: String, value: String) {
        lock.lock()
        defer { lock.unlock() }

        guard let data = value.data(using: .utf8) else { return }

        let baseQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]

        let attributesToUpdate: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let updateStatus = SecItemUpdate(baseQuery as CFDictionary, attributesToUpdate as CFDictionary)
        if updateStatus == errSecItemNotFound {
            var addQuery = baseQuery
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }

    func remove(key: String) {
        lock.lock()
        defer { lock.unlock() }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// `UserDefaults`-backed `KeyValueStore` for non-secret session metadata
/// (server URL, user profile, pending OIDC login state, and session active marker).
final class UserDefaultsKeyValueStore: NSObject, KeyValueStore {
    private let defaults: UserDefaults
    private let keyPrefix: String

    init(defaults: UserDefaults = .standard, keyPrefix: String = "pw.kmr.sonnet.session.") {
        self.defaults = defaults
        self.keyPrefix = keyPrefix
        super.init()
    }

    func getString(key: String) -> String? {
        defaults.string(forKey: keyPrefix + key)
    }

    func setString(key: String, value: String) {
        defaults.set(value, forKey: keyPrefix + key)
    }

    func remove(key: String) {
        defaults.removeObject(forKey: keyPrefix + key)
    }
}
