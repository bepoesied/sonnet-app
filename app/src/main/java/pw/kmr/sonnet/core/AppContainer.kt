package pw.kmr.sonnet.core

import android.content.Context
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import okio.FileSystem
import pw.kmr.sonnet.BuildConfig
import pw.kmr.sonnet.auth.AppAuthPlatformProvider
import pw.kmr.sonnet.data.preferences.SessionRepository
import pw.kmr.sonnet.player.PlaybackController
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider
import pw.kmr.sonnet.shared.data.local.SonnetDatabase
import pw.kmr.sonnet.shared.data.local.buildSonnetDatabase
import pw.kmr.sonnet.shared.data.local.getSonnetDatabaseBuilder
import pw.kmr.sonnet.shared.data.preferences.AppSettingsRepository
import pw.kmr.sonnet.shared.data.preferences.createAppSettingsDataStore
import pw.kmr.sonnet.shared.library.LibraryRepository
import pw.kmr.sonnet.shared.library.libraryDownloadDirectory
import pw.kmr.sonnet.shared.remote.AuthApiClient
import pw.kmr.sonnet.shared.remote.AuthenticatedBooksApiClient
import pw.kmr.sonnet.shared.remote.BooksApiClient
import pw.kmr.sonnet.shared.remote.SonnetApiClient
import pw.kmr.sonnet.shared.sync.ProgressSyncer
import pw.kmr.sonnet.sync.SyncCoordinator

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val settingsRepository = AppSettingsRepository(createAppSettingsDataStore(applicationContext))
    val sessionRepository = SessionRepository(applicationContext)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder().build()

    val database: SonnetDatabase = buildSonnetDatabase(getSonnetDatabaseBuilder(applicationContext))

    val sonnetApiClient = SonnetApiClient(OkHttp.create {
        preconfigured = okHttpClient
    })

    val authApiClient = AuthApiClient(sonnetApiClient)
    val rawBooksApiClient = BooksApiClient(sonnetApiClient)
    val authSessionManager = AuthSessionManager(sessionRepository, authApiClient)
    val booksApiClient = AuthenticatedBooksApiClient(rawBooksApiClient, authSessionManager)

    val libraryRepository = LibraryRepository(
        booksApiClient = booksApiClient,
        libraryDao = database.libraryDao(),
        booksDirectory = libraryDownloadDirectory(applicationContext),
        fileSystem = FileSystem.SYSTEM
    )

    val platformAuthProvider: PlatformAuthProvider = AppAuthPlatformProvider(applicationContext)

    val loginRepository = LoginRepository(
        authApiClient = authApiClient,
        authSessionManager = authSessionManager,
        settingsRepository = settingsRepository,
        localLibraryCleaner = libraryRepository,
        enforcesHttps = BuildConfig.ENFORCE_HTTPS
    )

    val progressSyncer = ProgressSyncer(
        remoteDataSource = booksApiClient,
        playbackProgressDao = database.libraryDao()
    )

    val syncCoordinator = SyncCoordinator(applicationContext, progressSyncer)

    val playbackController = PlaybackController(
        context = applicationContext,
        libraryRepository = libraryRepository,
        libraryDao = database.libraryDao(),
        progressSyncer = progressSyncer
    )

    fun dispose() {
        playbackController.shutdown()
        sonnetApiClient.close()
        (platformAuthProvider as? AppAuthPlatformProvider)?.dispose()
        database.close()
    }
}
