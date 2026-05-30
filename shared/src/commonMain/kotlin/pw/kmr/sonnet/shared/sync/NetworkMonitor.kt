package pw.kmr.sonnet.shared.sync

interface NetworkMonitor {
    fun onNetworkAvailable(callback: () -> Unit)
    fun dispose()
}
