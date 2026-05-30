package pw.kmr.sonnet.shared.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.model.SonnetUser
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionRepository(private val context: Context) : SessionStore {

    companion object {
        const val PREFERENCES_NAME = "secure_session"
        const val SERVER_URL = "server_url"
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val USER_ID = "user_id"
        const val USER_NAME = "user_name"
        const val USER_AVATAR_URL = "user_avatar_url"
        const val REQUIRES_LOGIN = "requires_login"
    }

    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, 0)
    }

    private val tokenCrypto: TokenCrypto by lazy { TokenCrypto() }

    private val sessionState = MutableStateFlow<AuthSession?>(null)

    override val currentSession: Flow<AuthSession?> = sessionState

    override suspend fun saveSession(session: AuthSession) {
        withContext(Dispatchers.IO) {
            preferences.edit().apply {
                putString(SERVER_URL, session.serverUrl)
                putString(ACCESS_TOKEN, tokenCrypto.encrypt(session.accessToken))
                putString(REFRESH_TOKEN, tokenCrypto.encrypt(session.refreshToken))
                putLong(USER_ID, session.user.id)
                putString(USER_NAME, session.user.name)
                putString(USER_AVATAR_URL, session.user.avatarUrl)
                putBoolean(REQUIRES_LOGIN, false)
                apply()
            }
        }
        sessionState.value = session
    }

    override suspend fun markRequiresLogin() {
        withContext(Dispatchers.IO) {
            preferences.edit().putBoolean(REQUIRES_LOGIN, true).apply()
        }
        sessionState.value = storedSession()
    }

    override fun requiresLogin(): Boolean = preferences.getBoolean(REQUIRES_LOGIN, false)

    override suspend fun clearSession(retainServerUrl: Boolean) {
        withContext(Dispatchers.IO) {
            val serverUrl = preferences.getString(SERVER_URL, null)
            preferences.edit().apply {
                clear()
                if (retainServerUrl && !serverUrl.isNullOrBlank()) {
                    putString(SERVER_URL, serverUrl)
                }
                apply()
            }
        }
        sessionState.value = null
    }

    override fun savedServerUrl(): String? = preferences.getString(SERVER_URL, null)

    override suspend fun storedSession(): AuthSession? = withContext(Dispatchers.IO) {
        readSession()
    }

    override suspend fun loadStoredSession(): AuthSession? = withContext(Dispatchers.IO) {
        readSession()
    }.also { session ->
        sessionState.value = session
    }

    private fun readSession(): AuthSession? {
        val serverUrl = preferences.getString(SERVER_URL, null)
        val accessToken = getEncryptedString(preferences, ACCESS_TOKEN)
        val refreshToken = getEncryptedString(preferences, REFRESH_TOKEN)
        val userName = preferences.getString(USER_NAME, null)
        val userId = preferences.getLong(USER_ID, 0L)

        if (serverUrl.isNullOrBlank() ||
            accessToken.isNullOrBlank() ||
            refreshToken.isNullOrBlank() ||
            userName.isNullOrBlank() ||
            userId <= 0L
        ) {
            return null
        }

        return AuthSession(
            serverUrl = serverUrl,
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = SonnetUser(
                id = userId,
                name = userName,
                avatarUrl = preferences.getString(USER_AVATAR_URL, null)
            )
        )
    }

    private fun getEncryptedString(preferences: SharedPreferences, key: String): String? {
        val encrypted = preferences.getString(key, null) ?: return null
        return runCatching { tokenCrypto.decrypt(encrypted) }.getOrNull()
    }
}

private class TokenCrypto {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
    }

    fun decrypt(value: String): String {
        val encrypted = Base64.getDecoder().decode(value)
        require(encrypted.size > IV_SIZE_BYTES) { "Encrypted token payload is invalid." }

        val iv = encrypted.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = encrypted.copyOfRange(IV_SIZE_BYTES, encrypted.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val existingKey = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        return existingKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sonnet_session_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
