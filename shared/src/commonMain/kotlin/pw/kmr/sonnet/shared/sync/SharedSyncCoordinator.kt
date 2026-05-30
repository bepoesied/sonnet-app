package pw.kmr.sonnet.shared.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pw.kmr.sonnet.shared.ioDispatcher

class SharedSyncCoordinator(
    networkMonitor: NetworkMonitor,
    private val progressSyncer: ProgressSyncer
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        networkMonitor.onNetworkAvailable {
            scope.launch { progressSyncer.syncPending() }
        }
    }
}
