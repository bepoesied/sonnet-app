package pw.kmr.sonnet.data.local.entity

import androidx.room.Entity

@Entity(tableName = "downloaded_chapters", primaryKeys = ["bookId", "id"])
data class DownloadedChapterEntity(
    val bookId: String,
    val id: String,
    val title: String,
    val position: Int,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val durationMs: Long? = null,
    val mediaAssetId: String,
    val audioFilePath: String,
    val contentType: String? = null
)