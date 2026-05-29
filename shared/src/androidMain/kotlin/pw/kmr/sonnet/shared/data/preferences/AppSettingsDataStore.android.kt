package pw.kmr.sonnet.shared.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import java.io.File

fun createAppSettingsDataStore(context: Context): DataStore<Preferences> {
    val appContext = context.applicationContext
    return createAppSettingsDataStore(
        storage = FileStorage(
            serializer = PreferencesFileSerializer,
            produceFile = {
                appContext.filesDir.resolve(APP_SETTINGS_DATASTORE_FILE_NAME)
            }
        )
    )
}
