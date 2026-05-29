package pw.kmr.sonnet.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers

fun getSonnetDatabaseBuilder(context: Context): RoomDatabase.Builder<SonnetDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(SONNET_DATABASE_NAME)
    return Room.databaseBuilder<SonnetDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
}
