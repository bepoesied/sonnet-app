package pw.kmr.sonnet.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pw.kmr.sonnet.shared.model.AppSettings

class AppSettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            serverUrl = preferences[SERVER_URL],
            playbackSyncCadenceSeconds = preferences[PLAYBACK_SYNC_CADENCE_SECONDS] ?: 30L
        )
    }

    suspend fun setServerUrl(serverUrl: String) {
        dataStore.edit { preferences ->
            preferences[SERVER_URL] = serverUrl
        }
    }

    suspend fun setPlaybackSyncCadence(seconds: Long) {
        dataStore.edit { preferences ->
            preferences[PLAYBACK_SYNC_CADENCE_SECONDS] = seconds
        }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val PLAYBACK_SYNC_CADENCE_SECONDS = longPreferencesKey("playback_sync_cadence_seconds")
    }
}