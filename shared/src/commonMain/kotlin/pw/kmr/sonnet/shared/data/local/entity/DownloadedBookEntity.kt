package pw.kmr.sonnet.shared.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_books")
data class DownloadedBookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val description: String? = null,
    val coverFilePath: String? = null,
    val isCompleted: Boolean = false,
    val downloadedAtEpochMillis: Long = 0L
)
