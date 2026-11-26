package pw.kmr.sonnet.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactiveNetworkMonitorTest {

    @Test
    fun triggersOnOfflineToOnlineTransitionAndDebouncesRapidFlapping() {
        var nowMs = 0L
        var triggers = 0
        var started = 0
        var stopped = 0

        val monitor = ReactiveNetworkMonitor(
            initialOnline = false,
            monotonicMillis = { nowMs },
            onStartMonitoring = { started++ },
            onStopMonitoring = { stopped++ }
        )

        assertFalse(monitor.isOnline.value)
        monitor.onNetworkAvailable { triggers++ }
        assertEquals(1, started)

        monitor.updateOnlineStatus(true)
        assertTrue(monitor.isOnline.value)
        assertEquals(1, triggers)

        // Duplicate online signal should not re-trigger.
        monitor.updateOnlineStatus(true)
        assertEquals(1, triggers)

        // Rapid drop and reconnect within 60s should be debounced.
        nowMs = 10_000L
        monitor.updateOnlineStatus(false)
        assertFalse(monitor.isOnline.value)
        nowMs = 20_000L
        monitor.updateOnlineStatus(true)
        assertEquals(1, triggers)

        // Drop and reconnect after 60s should trigger again.
        nowMs = 65_000L
        monitor.updateOnlineStatus(false)
        nowMs = 70_000L
        monitor.updateOnlineStatus(true)
        assertEquals(2, triggers)

        monitor.dispose()
        assertEquals(1, stopped)
    }
}
