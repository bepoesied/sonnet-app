package pw.kmr.sonnet.shared.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks offline download state for a library item in the Room `downloads` table (schema v5).
 *
 * Note: [downloadedBytes] and [totalBytes] currently store completed and total chapter counts
 * rather than raw byte counts. The column names are preserved to avoid a breaking Room schema
 * migration; UI projection maps them to chapter-based download progress.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val libraryItemId: String,
    val localFilePath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val state: String = "queued",
    val errorMessage: String? = null
)
