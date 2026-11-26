package pw.kmr.sonnet.shared.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import pw.kmr.sonnet.shared.library.excludedAppDataRootUrl

@OptIn(ExperimentalForeignApi::class)
fun getSonnetDatabaseBuilder(): RoomDatabase.Builder<SonnetDatabase> {
    val dbDirectoryUrl = excludedDatabaseDirectoryUrl()
    val dbFilePath = requireNotNull(dbDirectoryUrl.path) + "/$SONNET_DATABASE_NAME"
    return Room.databaseBuilder<SonnetDatabase>(
        name = dbFilePath,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun excludedDatabaseDirectoryUrl(): NSURL {
    val rootUrl = excludedAppDataRootUrl()
    val dbDirectoryUrl = requireNotNull(
        rootUrl.URLByAppendingPathComponent("database", isDirectory = true)
    )
    NSFileManager.defaultManager.createDirectoryAtURL(
        url = dbDirectoryUrl,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return dbDirectoryUrl
}
