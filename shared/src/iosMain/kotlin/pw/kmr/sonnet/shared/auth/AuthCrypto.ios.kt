package pw.kmr.sonnet.shared.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "Random byte count must be non-negative." }
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "Failed to generate cryptographically secure random bytes ($status)." }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { digestPinned ->
        if (bytes.isEmpty()) {
            CC_SHA256(null, 0u, digestPinned.addressOf(0).reinterpret())
        } else {
            bytes.usePinned { inputPinned ->
                CC_SHA256(
                    inputPinned.addressOf(0),
                    bytes.size.toUInt(),
                    digestPinned.addressOf(0).reinterpret()
                )
            }
        }
    }
    return digest
}
