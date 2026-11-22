package pw.kmr.sonnet.shared.sync

import kotlinx.coroutines.flow.StateFlow

interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
    fun onNetworkAvailable(callback: () -> Unit)
    fun dispose()
}
