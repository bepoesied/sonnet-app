package pw.kmr.sonnet.shared.auth

import kotlin.io.encoding.Base64

data class AuthChallenge(
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String
)

interface AuthChallengeGenerator {
    fun generate(): AuthChallenge
}

object DefaultAuthChallengeGenerator : AuthChallengeGenerator {
    private val base64UrlNoPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    override fun generate(): AuthChallenge {
        val codeVerifier = base64UrlNoPadding.encode(secureRandomBytes(CODE_VERIFIER_BYTES))
        val codeChallenge = computePkceCodeChallenge(codeVerifier)
        val state = base64UrlNoPadding.encode(secureRandomBytes(STATE_BYTES))
        return AuthChallenge(
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
            state = state
        )
    }

    private const val CODE_VERIFIER_BYTES = 32
    private const val STATE_BYTES = 32
}

internal fun computePkceCodeChallenge(codeVerifier: String): String =
    Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT)
        .encode(sha256(codeVerifier.encodeToByteArray()))

internal fun constantTimeEquals(expected: String, actual: String): Boolean {
    val expectedBytes = expected.encodeToByteArray()
    val actualBytes = actual.encodeToByteArray()
    if (expectedBytes.size != actualBytes.size) return false
    var diff = 0
    for (i in expectedBytes.indices) {
        diff = diff or (expectedBytes[i].toInt() xor actualBytes[i].toInt())
    }
    return diff == 0
}

internal expect fun secureRandomBytes(size: Int): ByteArray

internal expect fun sha256(bytes: ByteArray): ByteArray
