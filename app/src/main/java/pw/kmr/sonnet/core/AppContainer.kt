package pw.kmr.sonnet.core

import android.content.Context
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import okio.FileSystem
import pw.kmr.sonnet.BuildConfig
import pw.kmr.sonnet.library.AndroidBookDownloadScheduler
import pw.kmr.sonnet.player.SonnetMediaSessionService
import pw.kmr.sonnet.shared.auth.AppAuthPlatformProvider
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider
import pw.kmr.sonnet.shared.auth.SessionRepository
import pw.kmr.sonnet.shared.data.local.SonnetDatabase
import pw.kmr.sonnet.shared.data.local.buildSonnetDatabase
import pw.kmr.sonnet.shared.data.local.dao.LibraryDao
import pw.kmr.sonnet.shared.data.local.dao.PlaybackProgressDao
import pw.kmr.sonnet.shared.data.local.getSonnetDatabaseBuilder
import pw.kmr.sonnet.shared.data.preferences.AppSettingsRepository
import pw.kmr.sonnet.shared.data.preferences.createAppSettingsDataStore
import pw.kmr.sonnet.shared.library.BookDownloadScheduler
import pw.kmr.sonnet.shared.library.LibraryRepository
import pw.kmr.sonnet.shared.library.libraryDownloadDirectory
import pw.kmr.sonnet.shared.playback.AndroidPlaybackEngine
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.remote.AuthApiClient
import pw.kmr.sonnet.shared.remote.AuthenticatedBooksApiClient
import pw.kmr.sonnet.shared.remote.BooksApiClient
import pw.kmr.sonnet.shared.remote.SonnetApiClient
import pw.kmr.sonnet.shared.sync.AndroidNetworkMonitor
import pw.kmr.sonnet.shared.sync.BackgroundSyncScheduler
import pw.kmr.sonnet.shared.sync.ProgressSyncer
import pw.kmr.sonnet.shared.sync.SharedSyncCoordinator
import pw.kmr.sonnet.sync.AndroidBackgroundSyncScheduler
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val settingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository(createAppSettingsDataStore(applicationContext))
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(applicationContext)
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val databaseDelegate = lazy {
        buildSonnetDatabase(getSonnetDatabaseBuilder(applicationContext))
    }
    val database: SonnetDatabase by databaseDelegate

    val libraryDao: LibraryDao by lazy { database.libraryDao() }
    val playbackProgressDao: PlaybackProgressDao by lazy { libraryDao }

    private val sonnetApiClientDelegate = lazy {
        SonnetApiClient(OkHttp.create {
            preconfigured = okHttpClient
        })
    }
    val sonnetApiClient: SonnetApiClient by sonnetApiClientDelegate

    val authApiClient: AuthApiClient by lazy { AuthApiClient(sonnetApiClient) }
    val rawBooksApiClient: BooksApiClient by lazy { BooksApiClient(sonnetApiClient) }
    val authSessionManager: AuthSessionManager by lazy {
        AuthSessionManager(sessionRepository, authApiClient)
    }
    val booksApiClient: AuthenticatedBooksApiClient by lazy {
        AuthenticatedBooksApiClient(rawBooksApiClient, authSessionManager)
    }

    val backgroundSyncScheduler: BackgroundSyncScheduler by lazy {
        AndroidBackgroundSyncScheduler(applicationContext)
    }
    val bookDownloadScheduler: BookDownloadScheduler by lazy {
        AndroidBookDownloadScheduler(applicationContext)
    }

    val progressSyncer: ProgressSyncer by lazy {
        ProgressSyncer(
            remoteDataSource = booksApiClient,
            playbackProgressDao = playbackProgressDao,
            backgroundSyncScheduler = backgroundSyncScheduler
        )
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            booksApiClient = booksApiClient,
            libraryDao = libraryDao,
            booksDirectory = libraryDownloadDirectory(applicationContext),
            fileSystem = FileSystem.SYSTEM,
            progressSyncer = progressSyncer,
            onBeforeDeleteBook = { bookId ->
                if (playbackOrchestratorDelegate.isInitialized()) {
                    playbackOrchestrator.stopIfLoaded(bookId)
                }
            },
            onBeforeClearLibrary = {
                if (playbackOrchestratorDelegate.isInitialized()) {
                    playbackOrchestrator.stopAndResetAndWait()
                }
            }
        )
    }

    private val platformAuthProviderDelegate = lazy<PlatformAuthProvider> {
        AppAuthPlatformProvider(applicationContext)
    }
    val platformAuthProvider: PlatformAuthProvider by platformAuthProviderDelegate

    val loginRepository: LoginRepository by lazy {
        LoginRepository(
            authApiClient = authApiClient,
            authSessionManager = authSessionManager,
            settingsRepository = settingsRepository,
            localLibraryCleaner = libraryRepository,
            enforcesHttps = BuildConfig.ENFORCE_HTTPS
        )
    }

    private val networkMonitorDelegate = lazy {
        AndroidNetworkMonitor(applicationContext)
    }
    val networkMonitor: AndroidNetworkMonitor by networkMonitorDelegate

    private val syncCoordinatorDelegate = lazy {
        SharedSyncCoordinator(networkMonitor, progressSyncer)
    }
    val syncCoordinator: SharedSyncCoordinator by syncCoordinatorDelegate

    val playbackEngine: AndroidPlaybackEngine by lazy {
        AndroidPlaybackEngine(
            context = applicationContext,
            serviceClass = SonnetMediaSessionService::class.java
        )
    }

    private val playbackOrchestratorDelegate = lazy {
        PlaybackOrchestrator(
            engine = playbackEngine,
            libraryRepository = libraryRepository,
            libraryDao = playbackProgressDao,
            progressSyncer = progressSyncer
        )
    }
    val playbackOrchestrator: PlaybackOrchestrator by playbackOrchestratorDelegate

    fun stopPlaybackIfInitialized() {
        if (playbackOrchestratorDelegate.isInitialized()) {
            playbackOrchestrator.stopAndReset()
        }
    }

    fun flushPlaybackProgressIfInitialized(forceSync: Boolean = true) {
        if (playbackOrchestratorDelegate.isInitialized()) {
            playbackOrchestrator.flushProgress(forceSync = forceSync)
        }
    }

    fun stopSyncCoordinatorIfInitialized() {
        if (syncCoordinatorDelegate.isInitialized()) {
            syncCoordinator.stop()
        }
    }

    fun dispose() {
        if (syncCoordinatorDelegate.isInitialized()) {
            syncCoordinator.dispose()
        }
        if (networkMonitorDelegate.isInitialized()) {
            networkMonitor.dispose()
        }
        if (playbackOrchestratorDelegate.isInitialized()) {
            playbackOrchestrator.shutdown()
        }
        if (sonnetApiClientDelegate.isInitialized()) {
            sonnetApiClient.close()
        }
        if (platformAuthProviderDelegate.isInitialized()) {
            (platformAuthProvider as? AppAuthPlatformProvider)?.dispose()
        }
        if (databaseDelegate.isInitialized()) {
            database.close()
        }
    }
}
