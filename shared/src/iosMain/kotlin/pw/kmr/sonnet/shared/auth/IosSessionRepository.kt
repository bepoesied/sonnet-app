package pw.kmr.sonnet.shared.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.model.SonnetUser

@OptIn(ExperimentalForeignApi::class)
class IosSessionRepository : SessionStore {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val sessionState = MutableStateFlow<AuthSession?>(null)

    override val currentSession: Flow<AuthSession?> = sessionState

    override suspend fun saveSession(session: AuthSession) {
        withContext(Dispatchers.Default) {
            defaults.setObject(session.serverUrl, forKey = KEY_SERVER_URL)
            defaults.setObject(session.user.id.toString(), forKey = KEY_USER_ID)
            defaults.setObject(session.user.name, forKey = KEY_USER_NAME)
            defaults.setObject(session.user.avatarUrl ?: "", forKey = KEY_USER_AVATAR_URL)
            defaults.setBool(false, forKey = KEY_REQUIRES_LOGIN)
            defaults.synchronize()

            keychainSave(KEY_ACCESS_TOKEN, session.accessToken)
            keychainSave(KEY_REFRESH_TOKEN, session.refreshToken)
        }
        sessionState.value = session
    }

    override suspend fun markRequiresLogin() {
        withContext(Dispatchers.Default) {
            defaults.setBool(true, forKey = KEY_REQUIRES_LOGIN)
            defaults.synchronize()
        }
        sessionState.value = storedSession()
    }

    override fun requiresLogin(): Boolean = defaults.boolForKey(KEY_REQUIRES_LOGIN)

    override suspend fun clearSession(retainServerUrl: Boolean) {
        withContext(Dispatchers.Default) {
            val serverUrl = defaults.stringForKey(KEY_SERVER_URL)
            keychainDelete(KEY_ACCESS_TOKEN)
            keychainDelete(KEY_REFRESH_TOKEN)
            defaults.removeObjectForKey(KEY_SERVER_URL)
            defaults.removeObjectForKey(KEY_USER_ID)
            defaults.removeObjectForKey(KEY_USER_NAME)
            defaults.removeObjectForKey(KEY_USER_AVATAR_URL)
            defaults.removeObjectForKey(KEY_REQUIRES_LOGIN)
            if (retainServerUrl && !serverUrl.isNullOrBlank()) {
                defaults.setObject(serverUrl, forKey = KEY_SERVER_URL)
            }
            defaults.synchronize()
        }
        sessionState.value = null
    }

    override fun savedServerUrl(): String? = defaults.stringForKey(KEY_SERVER_URL)

    override suspend fun storedSession(): AuthSession? = withContext(Dispatchers.Default) {
        readSession()
    }

    override suspend fun loadStoredSession(): AuthSession? = withContext(Dispatchers.Default) {
        readSession()
    }.also { session ->
        sessionState.value = session
    }

    private fun readSession(): AuthSession? {
        val serverUrl = defaults.stringForKey(KEY_SERVER_URL)
        val accessToken = keychainLoad(KEY_ACCESS_TOKEN)
        val refreshToken = keychainLoad(KEY_REFRESH_TOKEN)
        val userName = defaults.stringForKey(KEY_USER_NAME)
        val userIdStr = defaults.stringForKey(KEY_USER_ID)
        val userId = userIdStr?.toLongOrNull() ?: 0L

        if (serverUrl.isNullOrBlank() ||
            accessToken.isNullOrBlank() ||
            refreshToken.isNullOrBlank() ||
            userName.isNullOrBlank() ||
            userId <= 0L
        ) {
            return null
        }

        val avatarUrl = defaults.stringForKey(KEY_USER_AVATAR_URL)?.takeIf { it.isNotBlank() }

        return AuthSession(
            serverUrl = serverUrl,
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = SonnetUser(
                id = userId,
                name = userName,
                avatarUrl = avatarUrl
            )
        )
    }

    private fun keychainSave(account: String, value: String) {
        keychainDelete(account)
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        memScoped {
            val query = platform.CoreFoundation.CFDictionaryCreateMutable(
                null, 4, null, null
            )
            platform.CoreFoundation.CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecAttrService, SERVICE_NAME as platform.CoreFoundation.CFTypeRef)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecAttrAccount, account as platform.CoreFoundation.CFTypeRef)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecValueData, data as platform.CoreFoundation.CFTypeRef)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            SecItemAdd(query, null)
            platform.CoreFoundation.CFRelease(query)
        }
    }

    private fun keychainLoad(account: String): String? {
        memScoped {
            val query = platform.CoreFoundation.CFDictionaryCreateMutable(
                null, 4, null, null
            )
            platform.CoreFoundation.CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecAttrService, SERVICE_NAME as platform.CoreFoundation.CFTypeRef)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecAttrAccount, account as platform.CoreFoundation.CFTypeRef)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecReturnData, platform.CoreFoundation.kCFBooleanTrue)

            val result = alloc<platform.CoreFoundation.CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            platform.CoreFoundation.CFRelease(query)

            if (status != errSecSuccess) return null
            val data = result.value as? NSData ?: return null
            return NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
        }
    }

    private fun keychainDelete(account: String) {
        memScoped {
            val query = platform.CoreFoundation.CFDictionaryCreateMutable(
                null, 3, null, null
            )
            platform.CoreFoundation.CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecAttrService, SERVICE_NAME as platform.CoreFoundation.CFTypeRef)
            platform.CoreFoundation.CFDictionarySetValue(query, kSecAttrAccount, account as platform.CoreFoundation.CFTypeRef)
            SecItemDelete(query)
            platform.CoreFoundation.CFRelease(query)
        }
    }

    private companion object {
        const val SERVICE_NAME = "pw.kmr.sonnet.session"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_AVATAR_URL = "user_avatar_url"
        const val KEY_REQUIRES_LOGIN = "requires_login"
    }
}
