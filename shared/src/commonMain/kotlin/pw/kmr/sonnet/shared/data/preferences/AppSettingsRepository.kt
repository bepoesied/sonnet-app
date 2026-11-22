package pw.kmr.sonnet.shared.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pw.kmr.sonnet.shared.model.AppSettings

class AppSettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        val cadenceSeconds = (preferences[PLAYBACK_SYNC_CADENCE_SECONDS]
            ?: AppSettings.DEFAULT_PLAYBACK_SYNC_CADENCE_SECONDS)
            .coerceAtLeast(AppSettings.MIN_PLAYBACK_SYNC_CADENCE_SECONDS)
        AppSettings(playbackSyncCadenceSeconds = cadenceSeconds)
    }

    suspend fun setPlaybackSyncCadence(seconds: Long) {
        dataStore.edit { preferences ->
            preferences[PLAYBACK_SYNC_CADENCE_SECONDS] =
                seconds.coerceAtLeast(AppSettings.MIN_PLAYBACK_SYNC_CADENCE_SECONDS)
        }
    }

    private companion object {
        val PLAYBACK_SYNC_CADENCE_SECONDS = longPreferencesKey("playback_sync_cadence_seconds")
    }
}
