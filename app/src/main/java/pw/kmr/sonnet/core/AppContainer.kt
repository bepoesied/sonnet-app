package pw.kmr.sonnet.core

import android.content.Context
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import okio.FileSystem
import pw.kmr.sonnet.BuildConfig
import pw.kmr.sonnet.player.SonnetMediaSessionService
import pw.kmr.sonnet.shared.auth.AppAuthPlatformProvider
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider
import pw.kmr.sonnet.shared.auth.SessionRepository
import pw.kmr.sonnet.shared.data.local.SonnetDatabase
import pw.kmr.sonnet.shared.data.local.buildSonnetDatabase
import pw.kmr.sonnet.shared.data.local.getSonnetDatabaseBuilder
import pw.kmr.sonnet.shared.data.preferences.AppSettingsRepository
import pw.kmr.sonnet.shared.data.preferences.createAppSettingsDataStore
import pw.kmr.sonnet.shared.library.LibraryRepository
import pw.kmr.sonnet.shared.library.libraryDownloadDirectory
import pw.kmr.sonnet.shared.playback.AndroidPlaybackEngine
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.remote.AuthApiClient
import pw.kmr.sonnet.shared.remote.AuthenticatedBooksApiClient
import pw.kmr.sonnet.shared.remote.BooksApiClient
import pw.kmr.sonnet.shared.remote.SonnetApiClient
import pw.kmr.sonnet.shared.sync.AndroidNetworkMonitor
import pw.kmr.sonnet.shared.sync.ProgressSyncer
import pw.kmr.sonnet.shared.sync.SharedSyncCoordinator
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val settingsRepository = AppSettingsRepository(createAppSettingsDataStore(applicationContext))
    val sessionRepository = SessionRepository(applicationContext)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

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

    val networkMonitor = AndroidNetworkMonitor(applicationContext)
    val syncCoordinator = SharedSyncCoordinator(networkMonitor, progressSyncer)

    val playbackEngine = AndroidPlaybackEngine(
        context = applicationContext,
        serviceClass = SonnetMediaSessionService::class.java
    )

    val playbackOrchestrator = PlaybackOrchestrator(
        engine = playbackEngine,
        libraryRepository = libraryRepository,
        libraryDao = database.libraryDao(),
        progressSyncer = progressSyncer
    )

    fun dispose() {
        playbackOrchestrator.shutdown()
        sonnetApiClient.close()
        (platformAuthProvider as? AppAuthPlatformProvider)?.dispose()
        database.close()
    }
}
