package pw.kmr.sonnet.shared.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidNetworkMonitor(
    context: Context,
    private val nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) : NetworkMonitor {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()

    private val _isOnline = MutableStateFlow(isCurrentlyValidated())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastTriggerMs: Long = Long.MIN_VALUE
    private var hasTriggered: Boolean = false

    override fun onNetworkAvailable(callback: () -> Unit) {
        val manager = connectivityManager ?: return
        synchronized(lock) {
            networkCallback?.let { existing ->
                runCatching { manager.unregisterNetworkCallback(existing) }
            }
            val registeredCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    handleValidationChange(
                        validated = networkCapabilities.isValidatedInternet(),
                        callback = callback
                    )
                }

                override fun onLost(network: Network) {
                    _isOnline.value = isCurrentlyValidated()
                }

                override fun onUnavailable() {
                    _isOnline.value = false
                }
            }
            networkCallback = registeredCallback
            runCatching {
                manager.registerDefaultNetworkCallback(registeredCallback)
            }
        }
    }

    override fun dispose() {
        val manager = connectivityManager ?: return
        synchronized(lock) {
            networkCallback?.let { existing ->
                runCatching { manager.unregisterNetworkCallback(existing) }
            }
            networkCallback = null
        }
    }

    internal fun handleValidationChange(validated: Boolean, callback: () -> Unit) {
        val shouldInvoke = synchronized(lock) {
            val wasOnline = _isOnline.value
            _isOnline.value = validated
            if (!validated) return@synchronized false

            val now = nowElapsedRealtimeMs()
            val transitioningOnline = !wasOnline || !hasTriggered
            if (!transitioningOnline) return@synchronized false

            if (hasTriggered && now - lastTriggerMs < MIN_INTERVAL_MS) {
                return@synchronized false
            }
            hasTriggered = true
            lastTriggerMs = now
            true
        }
        if (shouldInvoke) {
            callback()
        }
    }

    private fun isCurrentlyValidated(): Boolean {
        val manager = connectivityManager ?: return false
        return runCatching {
            val activeNetwork = manager.activeNetwork ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
            capabilities.isValidatedInternet()
        }.getOrDefault(false)
    }

    private fun NetworkCapabilities.isValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}
