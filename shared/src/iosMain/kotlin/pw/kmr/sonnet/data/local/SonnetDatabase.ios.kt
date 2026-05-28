package pw.kmr.sonnet.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import kotlin.coroutines.CoroutineContext

actual val sonnetDatabaseCoroutineContext: CoroutineContext = Dispatchers.IO

fun getSonnetDatabaseBuilder(): RoomDatabase.Builder<SonnetDatabase> {
    val dbFilePath = documentDirectory() + "/" + SONNET_DATABASE_NAME
    return Room.databaseBuilder<SonnetDatabase>(name = dbFilePath)
}

private fun documentDirectory(): String {
    val directoryUrl: NSURL = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null
        )
    )
    return requireNotNull(directoryUrl.path)
}