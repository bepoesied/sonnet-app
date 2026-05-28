package pw.kmr.sonnet.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences

const val APP_SETTINGS_DATASTORE_FILE_NAME = "app_settings.preferences_pb"

fun createAppSettingsDataStore(storage: Storage<Preferences>): DataStore<Preferences> {
    return PreferenceDataStoreFactory.create(storage = storage)
}