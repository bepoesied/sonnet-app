package pw.kmr.sonnet.shared.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastTriggerMs = 0L

    override fun onNetworkAvailable(callback: () -> Unit) {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTriggerMs < MIN_INTERVAL_MS) return
                lastTriggerMs = now
                callback()
            }
        }
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
        }
    }

    override fun dispose() {
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}
