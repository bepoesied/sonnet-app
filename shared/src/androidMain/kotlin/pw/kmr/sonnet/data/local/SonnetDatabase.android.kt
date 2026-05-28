package pw.kmr.sonnet.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

actual val sonnetDatabaseCoroutineContext: CoroutineContext = Dispatchers.IO

fun getSonnetDatabaseBuilder(context: Context): RoomDatabase.Builder<SonnetDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(SONNET_DATABASE_NAME)
    return Room.databaseBuilder<SonnetDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
}