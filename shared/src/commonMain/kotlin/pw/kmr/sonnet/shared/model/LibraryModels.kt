package pw.kmr.sonnet.shared.model

data class LibraryBook(
    val id: String,
    val title: String,
    val author: String?,
    val isCompleted: Boolean,
    val isDownloaded: Boolean,
    val downloadStatus: DownloadStatus,
    val downloadedChapters: Long,
    val totalChapters: Long?,
    val localCoverUri: String?,
    val remoteCoverUrl: String?,
    val progressPercent: Float?,
    val totalDurationMillis: Long? = null
)

data class DownloadedBook(
    val id: String,
    val title: String,
    val author: String?,
    val coverFilePath: String?,
    val chapters: List<DownloadedChapter>
)

data class DownloadedChapter(
    val id: String,
    val title: String,
    val position: Int,
    val audioFilePath: String,
    val durationMs: Long?
)

enum class DownloadStatus {
    NotDownloaded,
    Queued,
    Downloading,
    Downloaded,
    Failed
}