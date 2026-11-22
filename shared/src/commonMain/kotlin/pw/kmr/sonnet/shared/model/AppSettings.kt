package pw.kmr.sonnet.shared.model

data class AppSettings(
    val playbackSyncCadenceSeconds: Long = DEFAULT_PLAYBACK_SYNC_CADENCE_SECONDS
) {
    companion object {
        const val DEFAULT_PLAYBACK_SYNC_CADENCE_SECONDS = 60L
        const val MIN_PLAYBACK_SYNC_CADENCE_SECONDS = 5L
    }
}
