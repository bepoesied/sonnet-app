package pw.kmr.sonnet.shared.model

data class AppSettings(
    val serverUrl: String? = null,
    val playbackSyncCadenceSeconds: Long = 30L
)