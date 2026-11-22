package pw.kmr.sonnet.shared.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

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
    val fileManager = NSFileManager.defaultManager
    val appSupportUrl = requireNotNull(
        fileManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
    )
    val dbDirectoryUrl = requireNotNull(
        appSupportUrl.URLByAppendingPathComponent("database", isDirectory = true)
    )
    fileManager.createDirectoryAtURL(
        url = dbDirectoryUrl,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    dbDirectoryUrl.setResourceValue(
        value = NSNumber(bool = true),
        forKey = NSURLIsExcludedFromBackupKey,
        error = null,
    )
    return dbDirectoryUrl
}
