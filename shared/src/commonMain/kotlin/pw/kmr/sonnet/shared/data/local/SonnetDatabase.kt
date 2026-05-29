package pw.kmr.sonnet.shared.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import pw.kmr.sonnet.shared.data.local.dao.LibraryDao
import pw.kmr.sonnet.shared.data.local.entity.DownloadEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedBookEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedChapterEntity
import pw.kmr.sonnet.shared.data.local.entity.LibraryItemEntity
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import kotlin.coroutines.CoroutineContext

const val SONNET_DATABASE_NAME = "sonnet.db"

@Database(
    entities = [
        LibraryItemEntity::class,
        DownloadEntity::class,
        DownloadedBookEntity::class,
        DownloadedChapterEntity::class,
        PlaybackProgressEntity::class
    ],
    version = 5,
    exportSchema = false
)
@ConstructedBy(SonnetDatabaseConstructor::class)
abstract class SonnetDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

@Suppress("KotlinNoActualForExpect")
expect object SonnetDatabaseConstructor : RoomDatabaseConstructor<SonnetDatabase> {
    override fun initialize(): SonnetDatabase
}

fun buildSonnetDatabase(builder: RoomDatabase.Builder<SonnetDatabase>): SonnetDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()
}
