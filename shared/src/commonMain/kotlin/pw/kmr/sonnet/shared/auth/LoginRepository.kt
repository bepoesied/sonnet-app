package pw.kmr.sonnet.shared.auth

import io.ktor.http.Url
import pw.kmr.sonnet.shared.model.MobileConfig
import pw.kmr.sonnet.shared.remote.AuthApiClient
import kotlin.random.Random

class LoginRepository(
    private val authApiClient: AuthApiClient,
    private val authSessionManager: AuthSessionManager,
    private val enforcesHttps: Boolean
) {
    suspend fun createPendingLogin(serverUrlInput: String): PendingLogin {
        val serverUrl = normalizeServerUrl(serverUrlInput)
        require(isAllowedUrl(serverUrl)) {
            "Enter a valid ${if (enforcesHttps) "HTTPS" else "HTTP or HTTPS"} Sonnet server URL."
        }

        val config = authApiClient.mobileConfig(serverUrl)
        require(config.responseType == RESPONSE_TYPE_CODE) { "Server returned unsupported OIDC response type." }
        require("S256" in config.codeChallengeMethodsSupported) { "OIDC provider does not support PKCE S256." }

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val nonce = generateCodeVerifier()

        return PendingLogin(
            serverUrl = serverUrl,
            clientId = config.clientId,
            scopes = config.scopes,
            authorizationEndpoint = config.authorizationEndpoint,
            tokenEndpoint = config.tokenEndpoint,
            endSessionEndpoint = config.endSessionEndpoint,
            codeChallenge = codeChallenge,
            codeVerifier = codeVerifier,
            nonce = nonce,
            responseType = RESPONSE_TYPE_CODE,
            mobileConfig = config
        )
    }

    suspend fun completeLogin(pending: PendingLogin, idToken: String) {
        val login = authApiClient.oidcLogin(pending.serverUrl, idToken)
        authSessionManager.saveSession(
            pw.kmr.sonnet.shared.model.AuthSession(
                serverUrl = login.serverUrl,
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                user = login.user
            )
        )
    }

    fun savedServerUrl(): String? = authSessionManager.savedServerUrl()

    private fun isAllowedUrl(serverUrl: String): Boolean {
        return try {
            val url = Url(serverUrl)
            val scheme = url.protocol.name
            url.host.isNotEmpty() && scheme in setOf("http", "https") && (!enforcesHttps || scheme == "https")
        } catch (_: Exception) {
            false
        }
    }

    internal fun normalizeServerUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private fun generateCodeVerifier(): String {
        val bytes = Random.nextBytes(CODE_VERIFIER_LENGTH)
        return base64UrlEncode(bytes)
    }

    private fun generateCodeChallenge(codeVerifier: String): String {
        val digest = sha256(codeVerifier.encodeToByteArray())
        return base64UrlEncode(digest)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            when {
                value < 26 -> ('a' + value).toString()
                value < 52 -> ('A' + (value - 26)).toString()
                value < 62 -> ('0' + (value - 52)).toString()
                value == 62 -> "+"
                else -> "/"
            }
        }.trimEnd('=')
    }

    private fun sha256(message: ByteArray): ByteArray {
        var h0 = 0x6a09e667.toInt()
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372.toInt()
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f.toInt()
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19.toInt()

        val k = intArrayOf(
            0x428a2f98, 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74.toInt(),
            0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc, 0x2de92c6f,
            0x4a7484aa, 0x5cb0a9dc, 0x76f988da.toInt(), 0x983e5152.toInt(),
            0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(),
            0xd5a79147.toInt(), 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138,
            0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb.toInt(), 0x81c2c92e.toInt(),
            0x92722c85.toInt(), 0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(),
            0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(),
            0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
            0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
        )

        val bitLength = message.size * 8L
        val paddedMessage = message.copyOf(message.size + 1)
        paddedMessage[message.size] = 0x80.toByte()

        val padLength = when {
            paddedMessage.size % 64 < 56 -> 56 - paddedMessage.size % 64
            else -> 120 - paddedMessage.size % 64
        }
        val fullMessage = paddedMessage.copyOf(paddedMessage.size + padLength + 8)
        for (i in 0 until 8) {
            fullMessage[fullMessage.size - 8 + i] = ((bitLength shr (56 - i * 8)) and 0xFF).toByte()
        }

        for (chunkStart in fullMessage.indices step 64) {
            val w = IntArray(64)
            for (i in 0 until 16) {
                w[i] = (fullMessage[chunkStart + i * 4].toInt() shl 24) or
                        (fullMessage[chunkStart + i * 4 + 1].toInt() and 0xFF shl 16) or
                        (fullMessage[chunkStart + i * 4 + 2].toInt() and 0xFF shl 8) or
                        (fullMessage[chunkStart + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = Integer.rotateRight(w[i - 15], 7) xor Integer.rotateRight(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = Integer.rotateRight(w[i - 2], 17) xor Integer.rotateRight(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = (w[i - 16] + s0 + w[i - 7] + s1) and 0xFFFFFFFF.toInt()
            }

            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7

            for (i in 0 until 64) {
                val S1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = (h + S1 + ch + k[i] + w[i]) and 0xFFFFFFFF.toInt()
                val S0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = (S0 + maj) and 0xFFFFFFFF.toInt()

                h = g; g = f; f = e; e = (d + temp1) and 0xFFFFFFFF.toInt()
                d = c; c = b; b = a; a = (temp1 + temp2) and 0xFFFFFFFF.toInt()
            }

            h0 = (h0 + a) and 0xFFFFFFFF.toInt()
            h1 = (h1 + b) and 0xFFFFFFFF.toInt()
            h2 = (h2 + c) and 0xFFFFFFFF.toInt()
            h3 = (h3 + d) and 0xFFFFFFFF.toInt()
            h4 = (h4 + e) and 0xFFFFFFFF.toInt()
            h5 = (h5 + f) and 0xFFFFFFFF.toInt()
            h6 = (h6 + g) and 0xFFFFFFFF.toInt()
            h7 = (h7 + h) and 0xFFFFFFFF.toInt()
        }

        return byteArrayOf(
            (h0 shr 24).toByte(), (h0 shr 16).toByte(), (h0 shr 8).toByte(), h0.toByte(),
            (h1 shr 24).toByte(), (h1 shr 16).toByte(), (h1 shr 8).toByte(), h1.toByte(),
            (h2 shr 24).toByte(), (h2 shr 16).toByte(), (h2 shr 8).toByte(), h2.toByte(),
            (h3 shr 24).toByte(), (h3 shr 16).toByte(), (h3 shr 8).toByte(), h3.toByte(),
            (h4 shr 24).toByte(), (h4 shr 16).toByte(), (h4 shr 8).toByte(), h4.toByte(),
            (h5 shr 24).toByte(), (h5 shr 16).toByte(), (h5 shr 8).toByte(), h5.toByte(),
            (h6 shr 24).toByte(), (h6 shr 16).toByte(), (h6 shr 8).toByte(), h6.toByte(),
            (h7 shr 24).toByte(), (h7 shr 16).toByte(), (h7 shr 8).toByte(), h7.toByte()
        )
    }

    private companion object {
        const val CODE_VERIFIER_LENGTH = 32
        const val RESPONSE_TYPE_CODE = "code"
    }
}