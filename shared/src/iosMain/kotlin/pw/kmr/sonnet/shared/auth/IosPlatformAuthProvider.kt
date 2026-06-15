package pw.kmr.sonnet.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class IosPlatformAuthProvider : PlatformAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun startAuthorization(pendingLogin: PendingLogin): Any {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateCodeVerifier()

        val authUrl = buildAuthorizationUrl(pendingLogin, codeChallenge, state)

        return AuthSessionData(
            authUrl = authUrl,
            codeVerifier = codeVerifier,
            state = state
        )
    }

    override suspend fun completeAuthorization(pendingLogin: PendingLogin, result: Any): String {
        val callbackResult = result as AuthCallbackResult
        val code = callbackResult.code
        val state = callbackResult.state
        val authData = callbackResult.authData

        if (state != null && state != authData.state) {
            error("OIDC state mismatch.")
        }

        return exchangeCodeForToken(
            tokenEndpoint = pendingLogin.tokenEndpoint,
            code = code,
            codeVerifier = authData.codeVerifier,
            clientId = pendingLogin.clientId,
            redirectUri = REDIRECT_URI
        )
    }

    private fun buildAuthorizationUrl(
        pendingLogin: PendingLogin,
        codeChallenge: String,
        state: String
    ): String {
        val scopes = pendingLogin.scopes.joinToString(" ")
        return "${pendingLogin.authorizationEndpoint}?" +
            "response_type=${pendingLogin.responseType}" +
            "&client_id=${pendingLogin.clientId}" +
            "&redirect_uri=${urlEncode(REDIRECT_URI)}" +
            "&scope=${urlEncode(scopes)}" +
            "&state=${urlEncode(state)}" +
            "&code_challenge=${urlEncode(codeChallenge)}" +
            "&code_challenge_method=S256"
    }

    private suspend fun exchangeCodeForToken(
        tokenEndpoint: String,
        code: String,
        codeVerifier: String,
        clientId: String,
        redirectUri: String
    ): String {
        val client = HttpClient(Darwin)
        try {
            val response = client.submitForm(
                url = tokenEndpoint,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("code_verifier", codeVerifier)
                    append("client_id", clientId)
                    append("redirect_uri", redirectUri)
                }
            )
            val body = response.bodyAsText()
            val tokenResponse = json.decodeFromString<TokenResponse>(body)
            return tokenResponse.idToken ?: error("OIDC provider did not return an id_token.")
        } finally {
            client.close()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    companion object {
        const val CALLBACK_SCHEME = "sonnet"
        const val REDIRECT_URI = "sonnet://auth/callback"

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(32)
            SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), bytes)
            return Base64.UrlSafe.encode(bytes).trimEnd('=')
        }

        fun generateCodeChallenge(verifier: String): String {
            val paddedVerifier = verifier + "=".repeat((4 - verifier.length % 4) % 4)
            val verifierBytes = Base64.UrlSafe.decode(paddedVerifier)
            val digest = sha256(verifierBytes)
            return Base64.UrlSafe.encode(digest).trimEnd('=')
        }

        private fun sha256(data: ByteArray): ByteArray {
            val result = ByteArray(32)
            data.copyInto(result)
            // Use CommonCrypto via platform interop
            platform.Security.SecRandomCopyBytes(kSecRandomDefault, 0u, null) // warmup
            // Simple SHA-256 implementation for Kotlin/Native
            return sha256Hash(data)
        }

        private fun sha256Hash(data: ByteArray): ByteArray {
            val h = intArrayOf(
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
            )
            val k = intArrayOf(
                0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
                0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
                0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
                0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
                0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
                0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
                0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
                0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
                0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
                0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
                0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
                0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
                0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
                0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
                0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
                0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
            )

            val msg = padMessage(data)
            val words = IntArray(64)

            for (chunk in msg.indices step 64) {
                for (i in 0 until 16) {
                    words[i] = ((msg[chunk + i * 4].toInt() and 0xFF) shl 24) or
                        ((msg[chunk + i * 4 + 1].toInt() and 0xFF) shl 16) or
                        ((msg[chunk + i * 4 + 2].toInt() and 0xFF) shl 8) or
                        (msg[chunk + i * 4 + 3].toInt() and 0xFF)
                }
                for (i in 16 until 64) {
                    val s0 = (words[i - 15] ushr 7 or (words[i - 15] shl 25)) xor
                        (words[i - 15] ushr 18 or (words[i - 15] shl 14)) xor
                        (words[i - 15] ushr 3)
                    val s1 = (words[i - 2] ushr 17 or (words[i - 2] shl 15)) xor
                        (words[i - 2] ushr 19 or (words[i - 2] shl 13)) xor
                        (words[i - 2] ushr 10)
                    words[i] = words[i - 16] + s0 + words[i - 7] + s1
                }

                var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
                var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]

                for (i in 0 until 64) {
                    val S1 = (e ushr 6 or (e shl 26)) xor (e ushr 11 or (e shl 21)) xor (e ushr 25 or (e shl 7))
                    val ch = (e and f) xor (e.inv() and g)
                    val temp1 = hh + S1 + ch + k[i] + words[i]
                    val S0 = (a ushr 2 or (a shl 30)) xor (a ushr 13 or (a shl 19)) xor (a ushr 22 or (a shl 10))
                    val maj = (a and b) xor (a and c) xor (b and c)
                    val temp2 = S0 + maj

                    hh = g; g = f; f = e; e = d + temp1
                    d = c; c = b; b = a; a = temp1 + temp2
                }

                h[0] += a; h[1] += b; h[2] += c; h[3] += d
                h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            }

            val result = ByteArray(32)
            for (i in 0 until 8) {
                result[i * 4] = (h[i] ushr 24).toByte()
                result[i * 4 + 1] = (h[i] ushr 16).toByte()
                result[i * 4 + 2] = (h[i] ushr 8).toByte()
                result[i * 4 + 3] = h[i].toByte()
            }
            return result
        }

        private fun padMessage(data: ByteArray): ByteArray {
            val originalLength = data.size
            val bitLength = originalLength.toLong() * 8
            val paddingLength = when {
                originalLength % 64 < 56 -> 56 - originalLength % 64
                else -> 120 - originalLength % 64
            }
            val padded = ByteArray(originalLength + paddingLength + 8)
            data.copyInto(padded)
            padded[originalLength] = 0x80.toByte()
            for (i in 0 until 8) {
                padded[padded.size - 8 + i] = (bitLength ushr (56 - i * 8)).toByte()
            }
            return padded
        }

        private fun urlEncode(value: String): String {
            return value.encodeToByteArray().joinToString("") { byte ->
                when {
                    byte in 0x30..0x39 -> byte.toInt().toChar().toString()
                    byte in 0x41..0x5A -> byte.toInt().toChar().toString()
                    byte in 0x61..0x7A -> byte.toInt().toChar().toString()
                    byte == 0x2D.toByte() || byte == 0x2E.toByte() || byte == 0x5F.toByte() || byte == 0x7E.toByte() ->
                        byte.toInt().toChar().toString()
                    else -> "%${(byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')}"
                }
            }
        }
    }
}

data class AuthSessionData(
    val authUrl: String,
    val codeVerifier: String,
    val state: String
)

data class AuthCallbackResult(
    val code: String,
    val state: String?,
    val authData: AuthSessionData
)

@Serializable
private data class TokenResponse(
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null
)
