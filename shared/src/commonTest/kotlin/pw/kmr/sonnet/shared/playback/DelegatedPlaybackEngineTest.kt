package pw.kmr.sonnet.shared.playback

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelegatedPlaybackEngineTest {

    @Test
    fun connectsDelegatesCommandsAndForwardsCallbacks() = runTest {
        val driver = FakePlaybackDriver()
        val engine = DelegatedPlaybackEngine(driver)
        val events = mutableListOf<String>()

        engine.addListener(object : PlaybackEngineListener {
            override fun onPlaybackStateChanged(isPlaying: Boolean, playbackState: Int) {
                events += "state:$isPlaying:$playbackState"
            }

            override fun onPositionDiscontinuity() {
                events += "discontinuity"
            }

            override fun onMediaItemTransition(index: Int, reason: Int) {
                events += "transition:$index:$reason"
            }

            override fun onPlayerError(message: String) {
                events += "error:$message"
            }
        })

        assertEquals(PlaybackConnectionState.DISCONNECTED, engine.connectionState.value)
        engine.connect()
        assertEquals(PlaybackConnectionState.CONNECTED, engine.connectionState.value)

        val items = listOf(
            MediaItemDescriptor("/books/1/c1.m4a", "Ch 1", "Book", "Author", null, "1"),
            MediaItemDescriptor("/books/1/c2.m4a", "Ch 2", "Book", "Author", null, "1")
        )
        engine.loadMedia(items, startIndex = 0, startPositionMs = 1_500L)
        engine.prepare()
        engine.play()

        assertEquals(2, engine.mediaItemCount())
        assertEquals(0, engine.currentMediaItemIndex())
        assertEquals(1_500L, engine.currentPositionMs())
        assertTrue(engine.isPlaying())

        driver.callback?.onMediaItemTransition(1, DelegatedPlaybackEngine.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        driver.callback?.onPositionDiscontinuity()
        driver.callback?.onPlaybackStateChanged(false, DelegatedPlaybackEngine.PLAYBACK_STATE_ENDED)

        assertEquals(
            listOf(
                "state:true:3",
                "transition:1:1",
                "discontinuity",
                "state:false:4"
            ),
            events
        )

        engine.release()
        assertEquals(PlaybackConnectionState.DISCONNECTED, engine.connectionState.value)
        assertFalse(engine.isPlaying())
    }

    @Test
    fun reportsFailedStateWhenDriverConnectFails() = runTest {
        val driver = FakePlaybackDriver(shouldConnect = false)
        val engine = DelegatedPlaybackEngine(driver)
        var lastError: String? = null
        engine.addListener(object : PlaybackEngineListener {
            override fun onPlayerError(message: String) {
                lastError = message
            }
        })

        engine.connect()

        assertEquals(PlaybackConnectionState.FAILED, engine.connectionState.value)
        assertTrue(!lastError.isNullOrBlank())
    }
}

private class FakePlaybackDriver(
    private val shouldConnect: Boolean = true
) : PlaybackPlatformDriver {
    var callback: PlaybackDriverCallback? = null
    private var items: List<MediaItemDescriptor> = emptyList()
    private var index: Int = 0
    private var positionMs: Long = 0L
    private var playing: Boolean = false

    override fun attachCallback(callback: PlaybackDriverCallback?) {
        this.callback = callback
    }

    override fun connect(): Boolean = shouldConnect

    override fun release() {
        playing = false
        items = emptyList()
        index = 0
        positionMs = 0L
    }

    override fun loadMedia(items: List<MediaItemDescriptor>, startIndex: Int, startPositionMs: Long) {
        this.items = items
        this.index = startIndex
        this.positionMs = startPositionMs
    }

    override fun prepare() = Unit

    override fun play() {
        playing = true
        callback?.onPlaybackStateChanged(true, DelegatedPlaybackEngine.PLAYBACK_STATE_READY)
    }

    override fun pause() {
        playing = false
        callback?.onPlaybackStateChanged(false, DelegatedPlaybackEngine.PLAYBACK_STATE_READY)
    }

    override fun seekBack() {
        positionMs = (positionMs - SEEK_INCREMENT_MS).coerceAtLeast(0L)
        callback?.onPositionDiscontinuity()
    }

    override fun seekForward() {
        positionMs += SEEK_INCREMENT_MS
        callback?.onPositionDiscontinuity()
    }

    override fun seekToChapter(chapterIndex: Int, positionMs: Long) {
        this.index = chapterIndex
        this.positionMs = positionMs
        callback?.onPositionDiscontinuity()
    }

    override fun currentPositionMs(): Long = positionMs

    override fun currentMediaItemIndex(): Int = index

    override fun isPlaying(): Boolean = playing

    override fun mediaItemCount(): Int = items.size
}
