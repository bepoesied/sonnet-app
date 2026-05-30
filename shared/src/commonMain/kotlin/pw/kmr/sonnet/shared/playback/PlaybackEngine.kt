package pw.kmr.sonnet.shared.playback

import kotlinx.coroutines.flow.StateFlow

interface PlaybackEngine {
    val connectionState: StateFlow<PlaybackConnectionState>

    suspend fun connect()
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

    fun addListener(listener: PlaybackEngineListener)
    fun removeListener(listener: PlaybackEngineListener)
}

enum class PlaybackConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

data class MediaItemDescriptor(
    val uri: String,
    val title: String,
    val albumTitle: String,
    val artist: String?,
    val artworkUri: String?
)

interface PlaybackEngineListener {
    fun onPlaybackStateChanged(isPlaying: Boolean, playbackState: Int) {}
    fun onPositionDiscontinuity() {}
    fun onMediaItemTransition(index: Int, reason: Int) {}
    fun onPlayerError(message: String) {}
}
