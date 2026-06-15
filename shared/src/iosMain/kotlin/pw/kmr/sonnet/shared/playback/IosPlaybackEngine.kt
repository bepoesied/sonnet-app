package pw.kmr.sonnet.shared.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVAudioSession
import platform.AVFoundation.AVAudioSessionCategoryPlayback
import platform.AVFoundation.currentTime
import platform.AVFoundation.play
import platform.AVFoundation.pause
import platform.AVFoundation.seekToTime
import platform.AVFoundation.rate
import platform.AVFoundation.status
import platform.AVFoundation.AVPlayerStatusReadyToPlay
import platform.AVFoundation.AVPlayerStatusFailed
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import kotlin.math.max

class IosPlaybackEngine : PlaybackEngine {

    private val _connectionState = MutableStateFlow(PlaybackConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PlaybackConnectionState> = _connectionState.asStateFlow()

    private var player: AVPlayer? = null
    private var playerItems: List<AVPlayerItem> = emptyList()
    private val listeners = mutableListOf<PlaybackEngineListener>()
    private var timeObserver: Any? = null
    private var endObserver: Any? = null
    private var currentItemIndex = 0

    override suspend fun connect() {
        _connectionState.value = PlaybackConnectionState.CONNECTING
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)

            player = AVPlayer()
            setupTimeObserver()
            setupEndObserver()
            _connectionState.value = PlaybackConnectionState.CONNECTED
        } catch (_: Exception) {
            _connectionState.value = PlaybackConnectionState.FAILED
            listeners.forEach { it.onPlayerError("Failed to initialize audio player.") }
        }
    }

    override fun release() {
        removeTimeObserver()
        removeEndObserver()
        player?.pause()
        player = null
        playerItems = emptyList()
        _connectionState.value = PlaybackConnectionState.DISCONNECTED
    }

    override fun loadMedia(items: List<MediaItemDescriptor>, startIndex: Int, startPositionMs: Long) {
        val p = player ?: return
        p.pause()

        playerItems = items.map { item ->
            val url = NSURL.fileURLWithPath(item.uri)
            AVPlayerItem.playerItemWithURL(url)
        }

        currentItemIndex = startIndex.coerceIn(0, playerItems.lastIndex.coerceAtLeast(0))

        if (playerItems.isNotEmpty()) {
            val startItem = playerItems[currentItemIndex]
            p.replaceCurrentItemWithPlayerItem(startItem)
            if (startPositionMs > 0) {
                val time = CMTimeMake(startPositionMs, 1000)
                p.seekToTime(time)
            }
        }
    }

    override fun prepare() {
        // AVPlayer auto-prepares on item load
    }

    override fun play() {
        player?.play()
        listeners.forEach { it.onPlaybackStateChanged(true, playbackStateInt()) }
    }

    override fun pause() {
        player?.pause()
        listeners.forEach { it.onPlaybackStateChanged(false, playbackStateInt()) }
    }

    override fun seekBack() {
        val p = player ?: return
        val currentMs = (CMTimeGetSeconds(p.currentTime()) * 1000).toLong()
        val targetMs = max(0L, currentMs - SEEK_INCREMENT_MS)
        p.seekToTime(CMTimeMake(targetMs, 1000))
        listeners.forEach { it.onPositionDiscontinuity() }
    }

    override fun seekForward() {
        val p = player ?: return
        val currentMs = (CMTimeGetSeconds(p.currentTime()) * 1000).toLong()
        val targetMs = currentMs + SEEK_INCREMENT_MS
        p.seekToTime(CMTimeMake(targetMs, 1000))
        listeners.forEach { it.onPositionDiscontinuity() }
    }

    override fun seekToChapter(chapterIndex: Int, positionMs: Long) {
        val p = player ?: return
        if (chapterIndex != currentItemIndex && chapterIndex in playerItems.indices) {
            currentItemIndex = chapterIndex
            p.replaceCurrentItemWithPlayerItem(playerItems[chapterIndex])
            listeners.forEach { it.onMediaItemTransition(chapterIndex, 0) }
        }
        p.seekToTime(CMTimeMake(positionMs, 1000))
        listeners.forEach { it.onPositionDiscontinuity() }
    }

    override fun currentPositionMs(): Long {
        val p = player ?: return 0L
        return max(0L, (CMTimeGetSeconds(p.currentTime()) * 1000).toLong())
    }

    override fun currentMediaItemIndex(): Int = currentItemIndex.coerceAtLeast(0)

    override fun isPlaying(): Boolean {
        val p = player ?: return false
        return p.rate > 0f
    }

    override fun mediaItemCount(): Int = playerItems.size

    override fun addListener(listener: PlaybackEngineListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlaybackEngineListener) {
        listeners.remove(listener)
    }

    private fun setupTimeObserver() {
        val p = player ?: return
        val interval = CMTimeMake(500, 1000)
        timeObserver = p.addPeriodicTimeObserverForInterval(
            interval = interval,
            queue = platform.dispatch.dispatch_get_main_queue()
        ) {
            listeners.forEach { it.onPlaybackStateChanged(isPlaying(), playbackStateInt()) }
        }
    }

    private fun removeTimeObserver() {
        val observer = timeObserver ?: return
        player?.removeTimeObserver(observer)
        timeObserver = null
    }

    private fun setupEndObserver() {
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { notification ->
            val endedItem = notification?.`object` as? AVPlayerItem
            val endedIndex = playerItems.indexOf(endedItem)
            if (endedIndex in 0 until playerItems.size - 1) {
                val nextIndex = endedIndex + 1
                currentItemIndex = nextIndex
                player?.replaceCurrentItemWithPlayerItem(playerItems[nextIndex])
                player?.play()
                listeners.forEach { it.onMediaItemTransition(nextIndex, MEDIA_ITEM_TRANSITION_REASON_AUTO) }
            } else if (endedIndex == playerItems.size - 1) {
                listeners.forEach { it.onPlaybackStateChanged(false, PLAYBACK_STATE_ENDED) }
            }
        }
    }

    private fun removeEndObserver() {
        val observer = endObserver ?: return
        NSNotificationCenter.defaultCenter.removeObserver(observer)
        endObserver = null
    }

    private fun playbackStateInt(): Int {
        val p = player ?: return PLAYBACK_STATE_IDLE
        return when (p.status) {
            AVPlayerStatusReadyToPlay -> if (isPlaying()) PLAYBACK_STATE_READY else PLAYBACK_STATE_BUFFERING
            AVPlayerStatusFailed -> PLAYBACK_STATE_ENDED
            else -> PLAYBACK_STATE_IDLE
        }
    }

    companion object {
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val PLAYBACK_STATE_IDLE = 1
        private const val PLAYBACK_STATE_BUFFERING = 2
        private const val PLAYBACK_STATE_READY = 3
        private const val PLAYBACK_STATE_ENDED = 4
        private const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
    }
}
