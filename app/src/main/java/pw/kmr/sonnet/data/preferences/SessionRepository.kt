import pw.kmr.sonnet.auth.SessionStore
class SessionRepository(private val context: Context) : SessionStore {
    override val currentSession: Flow<AuthSession?> = sessionState
    override suspend fun saveSession(session: AuthSession) {
    override suspend fun markRequiresLogin() {
    override fun requiresLogin(): Boolean = preferences.getBoolean(REQUIRES_LOGIN, false)
    override suspend fun clearSession(retainServerUrl: Boolean) {
    override fun savedServerUrl(): String? = preferences.getString(SERVER_URL, null)
    override suspend fun storedSession(): AuthSession? = withContext(Dispatchers.IO) { readSession() }
    override suspend fun loadStoredSession(): AuthSession? = storedSession().also { session ->
                putString(SERVER_URL, session.serverUrl)
                putString(ACCESS_TOKEN, tokenCrypto.encrypt(session.accessToken))
                putString(REFRESH_TOKEN, tokenCrypto.encrypt(session.refreshToken))
                putLong(USER_ID, session.user.id)
                putString(USER_NAME, session.user.name)
                putString(USER_AVATAR_URL, session.user.avatarUrl)
                putBoolean(REQUIRES_LOGIN, false)
            }
        withContext(Dispatchers.IO) {
            preferences.edit { putBoolean(REQUIRES_LOGIN, true) }
        }
        sessionState.value = storedSession()
        withContext(Dispatchers.IO) {
            val serverUrl = preferences.getString(SERVER_URL, null)
            preferences.edit {
                clear()
                if (retainServerUrl && !serverUrl.isNullOrBlank()) {
                    putString(SERVER_URL, serverUrl)
                }
    suspend fun storedSession(): AuthSession? = withContext(Dispatchers.IO) { readSession() }
    private fun SharedPreferences.getEncryptedString(key: String): String? = getString(key, null)
        ?.let { encrypted -> runCatching { tokenCrypto.decrypt(encrypted) }.getOrNull() }
        const val PREFERENCES_NAME = "secure_session"

private class TokenCrypto {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
    }

    fun decrypt(value: String): String {
        val encrypted = Base64.getDecoder().decode(value)
        require(encrypted.size > IV_SIZE_BYTES) { "Encrypted token payload is invalid." }

        val iv = encrypted.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = encrypted.copyOfRange(IV_SIZE_BYTES, encrypted.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
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
            putString(SERVER_URL, session.serverUrl)
            putString(ACCESS_TOKEN, session.accessToken)
            putString(REFRESH_TOKEN, session.refreshToken)
            putLong(USER_ID, session.user.id)
            putString(USER_NAME, session.user.name)
            putString(USER_AVATAR_URL, session.user.avatarUrl)
        sessionState.value = session
    suspend fun clearSession(retainServerUrl: Boolean = false) {
        val serverUrl = preferences.getString(SERVER_URL, null)
        preferences.edit {
            clear()
            if (retainServerUrl && !serverUrl.isNullOrBlank()) {
                putString(SERVER_URL, serverUrl)
            }
        sessionState.value = null
    fun savedServerUrl(): String? = preferences.getString(SERVER_URL, null)

    fun storedSession(): AuthSession? = readSession()

    private fun readSession(): AuthSession? {
        val serverUrl = preferences.getString(SERVER_URL, null)
        val accessToken = preferences.getString(ACCESS_TOKEN, null)
        val refreshToken = preferences.getString(REFRESH_TOKEN, null)
        val userName = preferences.getString(USER_NAME, null)
        val userId = preferences.getLong(USER_ID, 0L)

        return if (
            serverUrl.isNullOrBlank() ||
            accessToken.isNullOrBlank() ||
            refreshToken.isNullOrBlank() ||
            userName.isNullOrBlank() ||
            userId <= 0L
        ) {
            null
        } else {
            AuthSession(
                serverUrl = serverUrl,
                accessToken = accessToken,
                refreshToken = refreshToken,
                user = SonnetUser(
                    id = userId,
                    name = userName,
                    avatarUrl = preferences.getString(USER_AVATAR_URL, null)
                )
            )
    private fun createPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        @Suppress("DEPRECATION")
        return EncryptedSharedPreferences.create(
            context,
            "secure_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

        const val SERVER_URL = "server_url"
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val USER_ID = "user_id"
        const val USER_NAME = "user_name"
        const val USER_AVATAR_URL = "user_avatar_url"
    }

    suspend fun saveSession(session: AuthSession) {
        context.sessionDataStore.edit { preferences ->
            preferences[SERVER_URL] = session.serverUrl
            preferences[ACCESS_TOKEN] = session.accessToken
        }
    }

    suspend fun clearSession() {
        context.sessionDataStore.edit { preferences ->
            preferences.clear()
        }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
    }
}