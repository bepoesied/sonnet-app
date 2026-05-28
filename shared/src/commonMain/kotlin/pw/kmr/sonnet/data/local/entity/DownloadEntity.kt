package pw.kmr.sonnet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val libraryItemId: String,
    val localFilePath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val state: String = "queued",
    val errorMessage: String? = null
)