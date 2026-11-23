package pw.kmr.sonnet.shared.auth

import java.security.MessageDigest
import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "Random byte count must be non-negative." }
    return ByteArray(size).also(secureRandom::nextBytes)
}

internal actual fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
