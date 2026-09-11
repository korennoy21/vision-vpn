import Foundation
import Security

enum Vault {
    static func query() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "dev.sever.profile",
         kSecAttrAccount as String: "active",
         kSecAttrAccessGroup as String: Bundle.main.object(forInfoDictionaryKey: "SeverKeychainGroup") as! String]
    }
    static func save(_ data: Data) throws -> Data {
        var attributes = query()
        let update: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(attributes as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            attributes.merge(update) { _, new in new }
            let added = SecItemAdd(attributes as CFDictionary, nil)
            guard added == errSecSuccess else { throw SeverError.keychain(added) }
        } else if status != errSecSuccess { throw SeverError.keychain(status) }
        attributes = query()
        attributes[kSecReturnPersistentRef as String] = true
        var value: CFTypeRef?
        let result = SecItemCopyMatching(attributes as CFDictionary, &value)
        guard result == errSecSuccess, let ref = value as? Data else { throw SeverError.keychain(result) }
        return ref
    }
    static func load() throws -> Data {
        var attributes = query()
        attributes[kSecReturnData as String] = true
        var value: CFTypeRef?
        let status = SecItemCopyMatching(attributes as CFDictionary, &value)
        guard status == errSecSuccess, let data = value as? Data else { throw SeverError.keychain(status) }
        return data
    }
}
