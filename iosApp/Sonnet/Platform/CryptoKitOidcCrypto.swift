import CryptoKit
import Foundation
import Security
import sharedKit

/// Native iOS implementation of `OidcCryptoProvider` backed by `SecRandomCopyBytes` and `CryptoKit`.
final class CryptoKitOidcCrypto: NSObject, OidcCryptoProvider {
    func randomBytes(count: Int32) -> KotlinByteArray {
        let byteCount = max(Int(count), 0)
        var bytes = [UInt8](repeating: 0, count: byteCount)
        if byteCount > 0 {
            let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
            if status != errSecSuccess {
                for index in 0..<byteCount {
                    bytes[index] = UInt8.random(in: UInt8.min...UInt8.max)
                }
            }
        }
        return Self.toKotlinByteArray(bytes)
    }

    func sha256(data: KotlinByteArray) -> KotlinByteArray {
        let swiftData = Self.toData(data)
        let digest = SHA256.hash(data: swiftData)
        return Self.toKotlinByteArray(Array(digest))
    }

    private static func toData(_ kotlinBytes: KotlinByteArray) -> Data {
        let size = Int(kotlinBytes.size)
        var data = Data(capacity: size)
        for index in 0..<size {
            data.append(UInt8(bitPattern: kotlinBytes.get(index: Int32(index))))
        }
        return data
    }

    private static func toKotlinByteArray(_ bytes: [UInt8]) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(bytes.count))
        for (index, byte) in bytes.enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}
