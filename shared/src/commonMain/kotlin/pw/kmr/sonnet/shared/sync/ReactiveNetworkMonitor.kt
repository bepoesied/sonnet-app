package pw.kmr.sonnet.shared.sync

import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure-Kotlin [NetworkMonitor] driven by platform connectivity callbacks (such as iOS NWPathMonitor).
 *
 * Matches Android's reconnect debounce semantics: triggers [onNetworkAvailable] on offline-to-online
 * transitions (or the first observed online signal), throttled to at most once per [MIN_INTERVAL_MS].
 */
class ReactiveNetworkMonitor(
    initialOnline: Boolean = true,
    private val monotonicMillis: () -> Long = defaultMonotonicClock(),
    private val onStartMonitoring: () -> Unit = {},
    private val onStopMonitoring: () -> Unit = {}
) : NetworkMonitor {
    private val _isOnline = MutableStateFlow(initialOnline)
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var callback: (() -> Unit)? = null
    private var lastTriggerMs: Long = Long.MIN_VALUE
    private var hasTriggered: Boolean = false

    override fun onNetworkAvailable(callback: () -> Unit) {
        this.callback = callback
        onStartMonitoring()
    }

    override fun dispose() {
        callback = null
        onStopMonitoring()
    }

    fun updateOnlineStatus(online: Boolean) {
        val wasOnline = _isOnline.value
        _isOnline.value = online
        if (!online) return

        val activeCallback = callback ?: return
        val transitioningOnline = !wasOnline || !hasTriggered
        if (!transitioningOnline) return

        val now = monotonicMillis()
        if (hasTriggered && now - lastTriggerMs < MIN_INTERVAL_MS) {
            return
        }
        hasTriggered = true
        lastTriggerMs = now
        activeCallback()
    }

    companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}

private fun defaultMonotonicClock(): () -> Long {
    val startMark = TimeSource.Monotonic.markNow()
    return { startMark.elapsedNow().inWholeMilliseconds }
}
