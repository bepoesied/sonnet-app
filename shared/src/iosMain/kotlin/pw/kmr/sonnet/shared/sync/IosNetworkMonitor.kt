package pw.kmr.sonnet.shared.sync

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSinceReferenceDate
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

class IosNetworkMonitor : NetworkMonitor {

    private var monitor: kotlinx.cinterop.CPointer<platform.Network.nw_path_monitor>? = null
    private var lastTriggerMs = 0L

    override fun onNetworkAvailable(callback: () -> Unit) {
        val pathMonitor = nw_path_monitor_create() ?: return
        monitor = pathMonitor

        nw_path_monitor_set_update_handler(pathMonitor) { path ->
            if (path != null && nw_path_get_status(path) == nw_path_status_satisfied) {
                val now = (NSDate.timeIntervalSinceReferenceDate() * 1000).toLong()
                if (now - lastTriggerMs >= MIN_INTERVAL_MS) {
                    lastTriggerMs = now
                    callback()
                }
            }
        }

        nw_path_monitor_start(pathMonitor, dispatch_get_main_queue())
    }

    override fun dispose() {
        monitor?.let { nw_path_monitor_cancel(it) }
        monitor = null
    }

    private companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}
