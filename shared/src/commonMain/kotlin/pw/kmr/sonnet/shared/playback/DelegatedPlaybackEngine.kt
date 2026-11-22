package pw.kmr.sonnet.shared.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Low-level platform playback driver bridged by [DelegatedPlaybackEngine].
 *
 * Implementations (such as iOS AVFoundation) handle audio session management,
 * media playback, and system media controls, forwarding playback lifecycle
 * events back through [PlaybackDriverCallback].
 */
interface PlaybackPlatformDriver {
    fun attachCallback(callback: PlaybackDriverCallback?)

    fun connect(): Boolean

    fun release()

    fun loadMedia(items: List<MediaItemDescriptor>, startIndex: Int, startPositionMs: Long)

    fun prepare()

    fun play()

    fun pause()

    fun seekBack()

    fun seekForward()

    fun seekToChapter(chapterIndex: Int, positionMs: Long)

    fun currentPositionMs(): Long

    fun currentMediaItemIndex(): Int

    fun isPlaying(): Boolean

    fun mediaItemCount(): Int
}

interface PlaybackDriverCallback {
    fun onPlaybackStateChanged(isPlaying: Boolean, playbackState: Int)

    fun onPositionDiscontinuity()

    fun onMediaItemTransition(index: Int, reason: Int)

    fun onPlayerError(message: String)
}

/**
 * Shared [PlaybackEngine] implementation that manages [connectionState] and listener fan-out
 * while delegating platform audio operations to a [PlaybackPlatformDriver].
 */
class DelegatedPlaybackEngine(
    private val driver: PlaybackPlatformDriver
) : PlaybackEngine {
    private val _connectionState = MutableStateFlow(PlaybackConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PlaybackConnectionState> = _connectionState.asStateFlow()

    private val listeners = mutableListOf<PlaybackEngineListener>()

    private val driverCallback = object : PlaybackDriverCallback {
        override fun onPlaybackStateChanged(isPlaying: Boolean, playbackState: Int) {
            listeners.toList().forEach { it.onPlaybackStateChanged(isPlaying, playbackState) }
        }

        override fun onPositionDiscontinuity() {
            listeners.toList().forEach { it.onPositionDiscontinuity() }
        }

        override fun onMediaItemTransition(index: Int, reason: Int) {
            listeners.toList().forEach { it.onMediaItemTransition(index, reason) }
        }

        override fun onPlayerError(message: String) {
            listeners.toList().forEach { it.onPlayerError(message) }
        }
    }

    init {
        driver.attachCallback(driverCallback)
    }

    override suspend fun connect() {
        if (_connectionState.value == PlaybackConnectionState.CONNECTED) return
        _connectionState.value = PlaybackConnectionState.CONNECTING
        driver.attachCallback(driverCallback)
        val connected = try {
            driver.connect()
        } catch (throwable: Throwable) {
            _connectionState.value = PlaybackConnectionState.FAILED
            driverCallback.onPlayerError(throwable.message ?: "Failed to connect to playback engine.")
            return
        }
        if (connected) {
            _connectionState.value = PlaybackConnectionState.CONNECTED
        } else {
            _connectionState.value = PlaybackConnectionState.FAILED
            driverCallback.onPlayerError("Failed to connect to playback engine.")
        }
    }

    override fun release() {
        driver.release()
        _connectionState.value = PlaybackConnectionState.DISCONNECTED
    }

    override fun loadMedia(items: List<MediaItemDescriptor>, startIndex: Int, startPositionMs: Long) {
        driver.loadMedia(items, startIndex, startPositionMs)
    }

    override fun prepare() {
        driver.prepare()
    }

    override fun play() {
        driver.play()
    }

    override fun pause() {
        driver.pause()
    }

    override fun seekBack() {
        driver.seekBack()
    }

    override fun seekForward() {
        driver.seekForward()
    }

    override fun seekToChapter(chapterIndex: Int, positionMs: Long) {
        driver.seekToChapter(chapterIndex, positionMs)
    }

    override fun currentPositionMs(): Long = driver.currentPositionMs().coerceAtLeast(0L)

    override fun currentMediaItemIndex(): Int = driver.currentMediaItemIndex().coerceAtLeast(0)

    override fun isPlaying(): Boolean = driver.isPlaying()

    override fun mediaItemCount(): Int = driver.mediaItemCount().coerceAtLeast(0)

    override fun addListener(listener: PlaybackEngineListener) {
        if (listener !in listeners) {
            listeners.add(listener)
        }
    }

    override fun removeListener(listener: PlaybackEngineListener) {
        listeners.remove(listener)
    }

    companion object {
        const val PLAYBACK_STATE_IDLE = 1
        const val PLAYBACK_STATE_BUFFERING = 2
        const val PLAYBACK_STATE_READY = 3
        const val PLAYBACK_STATE_ENDED = 4
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = PlaybackOrchestrator.MEDIA_ITEM_TRANSITION_REASON_AUTO
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK = 2
    }
}
