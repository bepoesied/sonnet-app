package pw.kmr.sonnet.shared.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun createAppSettingsDataStore(): DataStore<Preferences> {
    return createAppSettingsDataStore(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = {
                val appSupportDirectory = requireNotNull(
                    NSFileManager.defaultManager.URLForDirectory(
                        directory = NSApplicationSupportDirectory,
                        inDomain = NSUserDomainMask,
                        appropriateForURL = null,
                        create = true,
                        error = null,
                    )
                )
                (requireNotNull(appSupportDirectory.path) + "/$APP_SETTINGS_DATASTORE_FILE_NAME").toPath()
            }
        )
    )
}
