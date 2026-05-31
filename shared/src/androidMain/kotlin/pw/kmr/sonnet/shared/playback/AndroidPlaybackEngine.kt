package pw.kmr.sonnet.shared.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.math.max

class AndroidPlaybackEngine(
    private val context: Context,
    private val serviceClass: Class<*>
) : PlaybackEngine {
    private val applicationContext = context.applicationContext
    private val _connectionState = MutableStateFlow(PlaybackConnectionState.DISCONNECTED)

    override val connectionState: StateFlow<PlaybackConnectionState> = _connectionState.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val listeners = mutableListOf<PlaybackEngineListener>()
    private var pendingPlayAfterConnect = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED)
            ) {
                listeners.forEach { it.onPlaybackStateChanged(player.isPlaying, player.playbackState) }
            }
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                listeners.forEach { it.onPositionDiscontinuity() }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            listeners.forEach { it.onMediaItemTransition(controller?.currentMediaItemIndex?.coerceAtLeast(0) ?: 0, reason) }
        }

        override fun onPlayerError(error: PlaybackException) {
            listeners.forEach { it.onPlayerError(error.message ?: "Playback failed.") }
        }
    }

    override suspend fun connect() {
        _connectionState.value = PlaybackConnectionState.CONNECTING
        val token = SessionToken(
            applicationContext,
            ComponentName(applicationContext, serviceClass)
        )
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        controllerFuture = future
        Futures.addCallback(
            future,
            object : FutureCallback<MediaController> {
                override fun onSuccess(result: MediaController) {
                    controller = result
                    result.addListener(playerListener)
                    _connectionState.value = PlaybackConnectionState.CONNECTED
                    if (pendingPlayAfterConnect) {
                        pendingPlayAfterConnect = false
                        result.play()
                    }
                }

                override fun onFailure(t: Throwable) {
                    _connectionState.value = PlaybackConnectionState.FAILED
                    listeners.forEach { it.onPlayerError("Failed to connect to playback service.") }
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    override fun release() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        controllerFuture?.cancel(true)
        controllerFuture = null
        _connectionState.value = PlaybackConnectionState.DISCONNECTED
    }

    override fun loadMedia(items: List<MediaItemDescriptor>, startIndex: Int, startPositionMs: Long) {
        val c = controller ?: return
        c.setMediaItems(
            items.map { item ->
                MediaItem.Builder()
                    .setUri(item.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setAlbumTitle(item.albumTitle)
                            .setArtist(item.artist)
                            .setArtworkUri(item.artworkUri?.let { Uri.fromFile(File(it)) })
                            .build()
                    )
                    .build()
            },
            startIndex,
            startPositionMs
        )
    }

    override fun prepare() {
        controller?.prepare()
    }

    override fun play() {
        val c = controller
        if (c != null) {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, serviceClass)
            )
            c.play()
        } else {
            pendingPlayAfterConnect = true
        }
    }

    override fun pause() {
        controller?.pause()
    }

    override fun seekBack() {
        controller?.seekBack()
    }

    override fun seekForward() {
        controller?.seekForward()
    }

    override fun seekToChapter(chapterIndex: Int, positionMs: Long) {
        controller?.seekTo(chapterIndex, positionMs)
    }

    override fun currentPositionMs(): Long {
        return max(controller?.currentPosition ?: 0L, 0L)
    }

    override fun currentMediaItemIndex(): Int {
        return controller?.currentMediaItemIndex?.coerceAtLeast(0) ?: 0
    }

    override fun isPlaying(): Boolean {
        return controller?.isPlaying == true
    }

    override fun mediaItemCount(): Int {
        return controller?.mediaItemCount ?: 0
    }

    override fun addListener(listener: PlaybackEngineListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlaybackEngineListener) {
        listeners.remove(listener)
    }
}
