package pw.kmr.sonnet.shared.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Database migration chain:
 *
 * v1 → v2: Add isCompleted to library_items
 * v2 → v3: Add errorMessage to downloads; create downloaded_books, downloaded_chapters,
 *           playback_progress tables
 * v3 → v4: Recreate playback_progress with chapterId, chapterOffsetMillis, isCompleted
 *           columns (using temp table copy pattern)
 * v4 → v5: Add isCompleted column to playback_progress (for databases that were at v4
 *           without going through v3→v4 which already includes isCompleted)
 *
 * New installs get all migrations applied sequentially.
 */

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE library_items ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE downloads ADD COLUMN errorMessage TEXT")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_books (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT,
                narrator TEXT,
                description TEXT,
                coverFilePath TEXT,
                isCompleted INTEGER NOT NULL,
                downloadedAtEpochMillis INTEGER NOT NULL
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_chapters (
                bookId TEXT NOT NULL,
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                position INTEGER NOT NULL,
                startMs INTEGER,
                endMs INTEGER,
                durationMs INTEGER,
                mediaAssetId TEXT NOT NULL,
                audioFilePath TEXT NOT NULL,
                contentType TEXT,
                PRIMARY KEY(bookId, id)
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playback_progress (
                libraryItemId TEXT NOT NULL PRIMARY KEY,
                positionMillis INTEGER NOT NULL DEFAULT 0,
                durationMillis INTEGER,
                updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0,
                pendingSync INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playback_progress_new (
                libraryItemId TEXT NOT NULL PRIMARY KEY,
                chapterId TEXT,
                chapterOffsetMillis INTEGER NOT NULL DEFAULT 0,
                positionMillis INTEGER NOT NULL DEFAULT 0,
                durationMillis INTEGER,
                updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0,
                isCompleted INTEGER NOT NULL DEFAULT 0,
                pendingSync INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            INSERT OR REPLACE INTO playback_progress_new (
                libraryItemId,
                positionMillis,
                durationMillis,
                updatedAtEpochMillis,
                isCompleted,
                pendingSync
            )
            SELECT
                libraryItemId,
                positionMillis,
                durationMillis,
                updatedAtEpochMillis,
                0,
                pendingSync
            FROM playback_progress
            """.trimIndent()
        )
        connection.execSQL("DROP TABLE playback_progress")
        connection.execSQL("ALTER TABLE playback_progress_new RENAME TO playback_progress")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE playback_progress ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0")
    }
}
