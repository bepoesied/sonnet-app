private const val MIN_BUFFER_MS = 120_000
private const val MAX_BUFFER_MS = 600_000
private const val BACK_BUFFER_MS = 60_000
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS,
                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                    )
                    .setBackBuffer(BACK_BUFFER_MS, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )

private const val MIN_BUFFER_MS = 60_000
private const val MAX_BUFFER_MS = 300_000
private const val BUFFER_FOR_PLAYBACK_MS = 250
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500
private const val BACK_BUFFER_MS = 30_000
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
        val player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true
                )
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(
                        AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                            .build()
                    )
                    .build()
            }

        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build())
        mediaSession = MediaSession.Builder(this, player).build()
        mediaSession?.run {
            player.release()
            release()
        }
import pw.kmr.sonnet.SonnetApplication

class SonnetMediaSessionService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val controller = (application as SonnetApplication).appContainer.playbackController
        mediaSession = MediaSession.Builder(this, controller.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}