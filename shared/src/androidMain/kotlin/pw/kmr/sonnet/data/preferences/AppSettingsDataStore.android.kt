import androidx.datastore.preferences.core.PreferencesFileSerializer
            serializer = PreferencesFileSerializer,
            produceFile = { appContext.filesDir.resolve(APP_SETTINGS_DATASTORE_FILE_NAME) }
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer

fun createAppSettingsDataStore(context: Context): DataStore<Preferences> {
    val appContext = context.applicationContext
    return createAppSettingsDataStore(
        storage = FileStorage(
            serializer = PreferencesSerializer,
            produceFile = {
                appContext.filesDir.resolve("datastore").resolve(APP_SETTINGS_DATASTORE_FILE_NAME)
            }
        )
    )
}