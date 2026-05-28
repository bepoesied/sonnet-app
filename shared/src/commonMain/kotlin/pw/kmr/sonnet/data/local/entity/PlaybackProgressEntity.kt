package pw.kmr.sonnet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val libraryItemId: String,
    val chapterId: String? = null,
    val chapterOffsetMillis: Long = 0L,
    val positionMillis: Long = 0L,
    val durationMillis: Long? = null,
    val updatedAtEpochMillis: Long = 0L,
    val isCompleted: Boolean = false,
    val pendingSync: Boolean = false
)