package pw.kmr.sonnet.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pw.kmr.sonnet.shared.sync.ProgressSyncer

class SyncCoordinator(
    context: Context,
    private val progressSyncer: ProgressSyncer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var lastNetworkSyncMs = 0L

    init {
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        syncAfterNetworkAvailable()
                    }
                }
            )
        }
        syncAfterNetworkAvailable()
    }

    private fun syncAfterNetworkAvailable() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetworkSyncMs < MIN_NETWORK_SYNC_INTERVAL_MS) return
        lastNetworkSyncMs = now
        scope.launch { progressSyncer.syncPending() }
    }

    private companion object {
        const val MIN_NETWORK_SYNC_INTERVAL_MS = 60_000L
    }
}
