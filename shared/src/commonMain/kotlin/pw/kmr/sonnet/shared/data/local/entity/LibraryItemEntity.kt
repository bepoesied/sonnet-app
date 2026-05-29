package pw.kmr.sonnet.shared.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "library_items")
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String? = null,
    val coverImageUrl: String? = null,
    val isCompleted: Boolean = false,
    val updatedAtEpochMillis: Long = 0L
)
