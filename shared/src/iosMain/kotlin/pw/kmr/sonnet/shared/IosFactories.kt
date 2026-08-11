package pw.kmr.sonnet.shared

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import pw.kmr.sonnet.shared.data.local.SonnetDatabase
import okio.Path

object IosFactories {
    fun createAppSettingsDataStore(): DataStore<Preferences> {
        return pw.kmr.sonnet.shared.data.preferences.iosCreateAppSettingsDataStore()
    }

    fun getSonnetDatabaseBuilder(): androidx.room.RoomDatabase.Builder<SonnetDatabase> {
        return pw.kmr.sonnet.shared.data.local.iosGetSonnetDatabaseBuilder()
    }

    fun buildSonnetDatabase(): SonnetDatabase {
        return pw.kmr.sonnet.shared.data.local.buildSonnetDatabase(
            pw.kmr.sonnet.shared.data.local.iosGetSonnetDatabaseBuilder()
        )
    }

    fun libraryDownloadDirectory(): Path {
        return pw.kmr.sonnet.shared.library.iosLibraryDownloadDirectory()
    }
}
