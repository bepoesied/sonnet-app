package pw.kmr.sonnet.shared.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pw.kmr.sonnet.shared.ioDispatcher

class SharedSyncCoordinator(
    private val networkMonitor: NetworkMonitor,
    private val progressSyncer: ProgressSyncer,
    dispatcher: CoroutineDispatcher = ioDispatcher
) {
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + dispatcher)
    private val started = MutableStateFlow(false)
    private val disposed = MutableStateFlow(false)

    fun start() {
        if (disposed.value || !started.compareAndSet(expect = false, update = true)) return
        networkMonitor.onNetworkAvailable {
            triggerSync()
        }
        if (networkMonitor.isOnline.value) {
            triggerSync()
        }
    }

    fun stop() {
        if (!started.compareAndSet(expect = true, update = false)) return
        networkMonitor.dispose()
        rootJob.cancelChildren()
    }

    fun dispose() {
        if (!disposed.compareAndSet(expect = false, update = true)) return
        started.value = false
        networkMonitor.dispose()
        scope.cancel()
    }

    private fun triggerSync() {
        if (!started.value || disposed.value) return
        scope.launch {
            progressSyncer.syncPending()
        }
    }
}
