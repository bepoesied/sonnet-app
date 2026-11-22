package pw.kmr.sonnet.shared.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidPlaybackEngine(
    context: Context,
    private val serviceClass: Class<*>
) : PlaybackEngine {
    private val applicationContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(applicationContext)
    private val mainDispatcher = Dispatchers.Main.immediate
    private val connectMutex = Mutex()
    private val _connectionState = MutableStateFlow(PlaybackConnectionState.DISCONNECTED)

    override val connectionState: StateFlow<PlaybackConnectionState> = _connectionState.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val listeners = CopyOnWriteArrayList<PlaybackEngineListener>()
    private var pendingPlayAfterConnect = false

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@AndroidPlaybackEngine.controller === controller) {
                controller.removeListener(playerListener)
                this@AndroidPlaybackEngine.controller = null
                controllerFuture = null
                pendingPlayAfterConnect = false
                _connectionState.value = PlaybackConnectionState.DISCONNECTED
            }
        }
    }

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
        connectMutex.withLock {
            withContext(mainDispatcher) {
                val existing = controller
                if (existing != null && existing.isConnected && _connectionState.value == PlaybackConnectionState.CONNECTED) {
                    return@withContext
                }

                // Release any stale controller or pending future before creating a fresh connection.
                releaseOnMainThread()

                _connectionState.value = PlaybackConnectionState.CONNECTING
                val token = SessionToken(
                    applicationContext,
                    ComponentName(applicationContext, serviceClass)
                )
                val future = MediaController.Builder(applicationContext, token)
                    .setListener(controllerListener)
                    .buildAsync()
                controllerFuture = future

                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation {
                        runOnMain {
                            if (controllerFuture === future) {
                                MediaController.releaseFuture(future)
                                controllerFuture = null
                                controller = null
                                pendingPlayAfterConnect = false
                                if (_connectionState.value == PlaybackConnectionState.CONNECTING) {
                                    _connectionState.value = PlaybackConnectionState.DISCONNECTED
                                }
                            }
                        }
                    }

                    Futures.addCallback(
                        future,
                        object : FutureCallback<MediaController> {
                            override fun onSuccess(result: MediaController) {
                                if (controllerFuture !== future) {
                                    // release() or a newer connect() superseded this future; prevent leaking controller.
                                    result.release()
                                    if (continuation.isActive) {
                                        continuation.resume(Unit)
                                    }
                                    return
                                }
                                controller = result
                                result.addListener(playerListener)
                                _connectionState.value = PlaybackConnectionState.CONNECTED
                                if (pendingPlayAfterConnect) {
                                    pendingPlayAfterConnect = false
                                    result.play()
                                }
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onFailure(t: Throwable) {
                                if (controllerFuture === future) {
                                    controllerFuture = null
                                    controller = null
                                    pendingPlayAfterConnect = false
                                    if (t !is CancellationException && !future.isCancelled) {
                                        _connectionState.value = PlaybackConnectionState.FAILED
                                        listeners.forEach { it.onPlayerError("Failed to connect to playback service.") }
                                    } else if (_connectionState.value == PlaybackConnectionState.CONNECTING) {
                                        _connectionState.value = PlaybackConnectionState.DISCONNECTED
                                    }
                                }
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }
                        },
                        mainExecutor
                    )
                }
            }
        }
    }

    override fun release() {
        runOnMain {
            releaseOnMainThread()
        }
    }

    override fun loadMedia(items: List<MediaItemDescriptor>, startIndex: Int, startPositionMs: Long) {
        runOnMain {
            val c = controller ?: return@runOnMain
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
                                .apply {
                                    item.bookId?.let { bookId ->
                                        setExtras(Bundle().apply { putString(EXTRA_BOOK_ID, bookId) })
                                    }
                                }
                                .build()
                        )
                        .build()
                },
                startIndex,
                startPositionMs
            )
        }
    }

    override fun prepare() {
        runOnMain {
            controller?.prepare()
        }
    }

    override fun play() {
        runOnMain {
            val c = controller
            if (c != null) {
                c.play()
            } else {
                pendingPlayAfterConnect = true
            }
        }
    }

    override fun pause() {
        runOnMain {
            pendingPlayAfterConnect = false
            controller?.pause()
        }
    }

    override fun seekBack() {
        runOnMain {
            controller?.seekBack()
        }
    }

    override fun seekForward() {
        runOnMain {
            controller?.seekForward()
        }
    }

    override fun seekToChapter(chapterIndex: Int, positionMs: Long) {
        runOnMain {
            controller?.seekTo(chapterIndex, positionMs)
        }
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
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: PlaybackEngineListener) {
        listeners.remove(listener)
    }

    private fun releaseOnMainThread() {
        pendingPlayAfterConnect = false
        val currentController = controller
        val future = controllerFuture
        controller = null
        controllerFuture = null
        currentController?.removeListener(playerListener)
        if (future != null) {
            MediaController.releaseFuture(future)
        } else {
            currentController?.release()
        }
        _connectionState.value = PlaybackConnectionState.DISCONNECTED
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainExecutor.execute { block() }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "pw.kmr.sonnet.extra.BOOK_ID"
    }
}
