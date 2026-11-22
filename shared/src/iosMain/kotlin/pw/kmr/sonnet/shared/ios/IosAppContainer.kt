package pw.kmr.sonnet.shared.ios

import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.auth.KeyValueSessionStore
import pw.kmr.sonnet.shared.auth.KeyValueStore
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.OidcCryptoProvider
import pw.kmr.sonnet.shared.auth.OidcPkceAuthProvider
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider
import pw.kmr.sonnet.shared.core.AppViewModelHandle
import pw.kmr.sonnet.shared.core.FlowSubscription
import pw.kmr.sonnet.shared.core.LibraryViewModelHandle
import pw.kmr.sonnet.shared.core.LoginViewModelHandle
import pw.kmr.sonnet.shared.core.PlayerViewModelHandle
import pw.kmr.sonnet.shared.core.subscribe
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
import pw.kmr.sonnet.shared.playback.DelegatedPlaybackEngine
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.playback.PlaybackPlatformDriver
import pw.kmr.sonnet.shared.playback.PlayerUiState
import pw.kmr.sonnet.shared.remote.AuthApiClient
import pw.kmr.sonnet.shared.remote.AuthenticatedBooksApiClient
import pw.kmr.sonnet.shared.remote.BooksApiClient
import pw.kmr.sonnet.shared.remote.SonnetApiClient
import pw.kmr.sonnet.shared.sync.BackgroundSyncScheduler
import pw.kmr.sonnet.shared.sync.ProgressSyncer
import pw.kmr.sonnet.shared.sync.ReactiveNetworkMonitor
import pw.kmr.sonnet.shared.sync.SharedSyncCoordinator

/**
 * Native iOS platform adapters injected from Swift into [IosAppContainer].
 */
class IosPlatformDependencies(
    val secureStore: KeyValueStore,
    val preferencesStore: KeyValueStore,
    val oidcCrypto: OidcCryptoProvider,
    val audioDriver: PlaybackPlatformDriver,
    val bookDownloadScheduler: BookDownloadScheduler,
    val backgroundSyncScheduler: BackgroundSyncScheduler,
    val enforcesHttps: Boolean,
    val initialOnline: Boolean,
    val onStartNetworkMonitoring: () -> Unit,
    val onStopNetworkMonitoring: () -> Unit
)

/**
 * iOS dependency injection root mirroring Android's `AppContainer`.
 *
 * Uses lazy initialization for Room, DataStore, Ktor Darwin, and playback so cold start stays
 * fast and unauthenticated launches do not open the local library database prematurely.
 */
class IosAppContainer(
    private val deps: IosPlatformDependencies
) {
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadJobsMutex = Mutex()
    private val activeDownloadJobs = mutableMapOf<String, Job>()

    val settingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository(createAppSettingsDataStore())
    }

    val sessionStore: KeyValueSessionStore by lazy {
        KeyValueSessionStore(
            secureStore = deps.secureStore,
            preferencesStore = deps.preferencesStore
        )
    }

    private val databaseDelegate = lazy {
        buildSonnetDatabase(getSonnetDatabaseBuilder())
    }
    val database: SonnetDatabase by databaseDelegate

    val libraryDao: LibraryDao by lazy { database.libraryDao() }
    val playbackProgressDao: PlaybackProgressDao by lazy { libraryDao }

    private val sonnetApiClientDelegate = lazy {
        SonnetApiClient(
            Darwin.create {
                configureSession {
                    timeoutIntervalForRequest = REQUEST_TIMEOUT_SECONDS
                    timeoutIntervalForResource = RESOURCE_TIMEOUT_SECONDS
                }
            }
        )
    }
    val sonnetApiClient: SonnetApiClient by sonnetApiClientDelegate

    val authApiClient: AuthApiClient by lazy { AuthApiClient(sonnetApiClient) }
    val rawBooksApiClient: BooksApiClient by lazy { BooksApiClient(sonnetApiClient) }
    val authSessionManager: AuthSessionManager by lazy {
        AuthSessionManager(sessionStore, authApiClient)
    }
    val booksApiClient: AuthenticatedBooksApiClient by lazy {
        AuthenticatedBooksApiClient(rawBooksApiClient, authSessionManager)
    }

    val progressSyncer: ProgressSyncer by lazy {
        ProgressSyncer(
            remoteDataSource = booksApiClient,
            playbackProgressDao = playbackProgressDao,
            backgroundSyncScheduler = deps.backgroundSyncScheduler
        )
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            booksApiClient = booksApiClient,
            libraryDao = libraryDao,
            booksDirectory = libraryDownloadDirectory(),
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

    val platformAuthProvider: PlatformAuthProvider by lazy {
        OidcPkceAuthProvider(
            apiClient = sonnetApiClient,
            cryptoProvider = deps.oidcCrypto
        )
    }

    val loginRepository: LoginRepository by lazy {
        LoginRepository(
            authApiClient = authApiClient,
            authSessionManager = authSessionManager,
            localLibraryCleaner = libraryRepository,
            enforcesHttps = deps.enforcesHttps
        )
    }

    private val networkMonitorDelegate = lazy {
        ReactiveNetworkMonitor(
            initialOnline = deps.initialOnline,
            onStartMonitoring = deps.onStartNetworkMonitoring,
            onStopMonitoring = deps.onStopNetworkMonitoring
        )
    }
    val networkMonitor: ReactiveNetworkMonitor by networkMonitorDelegate

    private val syncCoordinatorDelegate = lazy {
        SharedSyncCoordinator(networkMonitor, progressSyncer)
    }
    val syncCoordinator: SharedSyncCoordinator by syncCoordinatorDelegate

    val playbackEngine: DelegatedPlaybackEngine by lazy {
        DelegatedPlaybackEngine(deps.audioDriver)
    }

    private val playbackOrchestratorDelegate = lazy {
        PlaybackOrchestrator(
            engine = playbackEngine,
            libraryRepository = libraryRepository,
            libraryDao = playbackProgressDao,
            progressSyncer = progressSyncer,
            settings = settingsRepository.settings
        )
    }
    val playbackOrchestrator: PlaybackOrchestrator by playbackOrchestratorDelegate

    fun createAppViewModelHandle(): AppViewModelHandle =
        AppViewModelHandle(loginRepository, authSessionManager)

    fun createLoginViewModelHandle(): LoginViewModelHandle =
        LoginViewModelHandle(loginRepository, platformAuthProvider)

    fun createLibraryViewModelHandle(): LibraryViewModelHandle =
        LibraryViewModelHandle(
            repository = libraryRepository,
            requiresReauth = authSessionManager.requiresLogin,
            downloadScheduler = deps.bookDownloadScheduler,
            isOnline = networkMonitor.isOnline
        )

    fun createPlayerViewModelHandle(bookId: String): PlayerViewModelHandle =
        PlayerViewModelHandle(playbackOrchestrator, bookId)

    fun observePlayerState(onChange: (PlayerUiState) -> Unit): FlowSubscription =
        playbackOrchestrator.state.subscribe(onEach = onChange)

    fun observePendingPlayerRequest(onChange: (String?) -> Unit): FlowSubscription =
        playbackOrchestrator.pendingPlayerRequest.subscribe(onEach = onChange)

    fun startSyncCoordinator() {
        syncCoordinator.start()
    }

    fun stopSyncCoordinatorIfInitialized() {
        if (syncCoordinatorDelegate.isInitialized()) {
            syncCoordinator.stop()
        }
    }

    fun stopPlaybackIfInitialized() {
        if (playbackOrchestratorDelegate.isInitialized()) {
            playbackOrchestrator.stopAndReset()
        }
    }

    fun flushPlaybackProgressIfInitialized(forceSync: Boolean) {
        if (playbackOrchestratorDelegate.isInitialized()) {
            playbackOrchestrator.flushProgress(forceSync = forceSync)
        }
    }

    fun onAppDidEnterBackground() {
        flushPlaybackProgressIfInitialized(forceSync = true)
    }

    suspend fun syncPendingProgressForBackgroundTask(): Boolean {
        if (!ensureAuthenticatedSessionLoaded()) {
            return true
        }
        val result = progressSyncer.syncPending()
        return result.authFailed || !result.hasTransientFailures
    }

    /**
     * Runs a cancellable Kotlin download coroutine tracked by [bookId].
     *
     * Swift's `Task.cancel()` does not automatically propagate into KMP ObjC-exported `suspend`
     * bridges, so [cancelBookDownload] and [cancelAllBookDownloads] cancel the backing Kotlin [Job]
     * directly.
     */
    suspend fun downloadBookForBackgroundTask(bookId: String, restartInProgress: Boolean) {
        if (!ensureAuthenticatedSessionLoaded()) {
            return
        }

        var failure: Throwable? = null
        val job = downloadJobsMutex.withLock {
            val existing = activeDownloadJobs[bookId]
            if (existing?.isActive == true) {
                existing
            } else {
                downloadScope.launch {
                    try {
                        libraryRepository.downloadBook(
                            bookId = bookId,
                            restartInProgress = restartInProgress
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        failure = throwable
                    }
                }.also { created ->
                    activeDownloadJobs[bookId] = created
                    created.invokeOnCompletion {
                        downloadScope.launch {
                            downloadJobsMutex.withLock {
                                if (activeDownloadJobs[bookId] === created) {
                                    activeDownloadJobs.remove(bookId)
                                }
                            }
                        }
                    }
                }
            }
        }

        job.join()
        if (job.isCancelled) {
            throw CancellationException("Download cancelled for book $bookId")
        }
        failure?.let { throw it }
    }

    fun cancelBookDownload(bookId: String) {
        downloadScope.launch {
            val job = downloadJobsMutex.withLock { activeDownloadJobs.remove(bookId) }
            job?.cancel()
        }
    }

    fun cancelAllBookDownloads() {
        downloadScope.launch {
            val jobs = downloadJobsMutex.withLock {
                activeDownloadJobs.values.toList().also { activeDownloadJobs.clear() }
            }
            jobs.forEach { it.cancel() }
        }
    }

    suspend fun interruptedDownloadBookIds(): List<String> {
        if (!ensureAuthenticatedSessionLoaded()) {
            return emptyList()
        }
        return libraryRepository.interruptedDownloadBookIds()
    }

    fun dispose() {
        downloadScope.cancel()
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
        if (databaseDelegate.isInitialized()) {
            database.close()
        }
    }

    private suspend fun ensureAuthenticatedSessionLoaded(): Boolean {
        if (authSessionManager.currentSession.value == null) {
            authSessionManager.loadStoredSession()
        }
        return authSessionManager.currentSession.value != null &&
            !authSessionManager.requiresLogin.value
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 60.0
        const val RESOURCE_TIMEOUT_SECONDS = 600.0
    }
}
