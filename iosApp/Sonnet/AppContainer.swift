import Foundation
import sharedKit

@MainActor
class AppContainer {
    let settingsRepository: AppSettingsRepository
    let sessionRepository: IosSessionRepository
    let database: SonnetDatabase
    let sonnetApiClient: SonnetApiClient
    let authApiClient: AuthApiClient
    let authSessionManager: AuthSessionManager
    let booksApiClient: AuthenticatedBooksApiClient
    let libraryRepository: LibraryRepository
    let platformAuthProvider: IosPlatformAuthProvider
    let loginRepository: LoginRepository
    let playbackOrchestrator: PlaybackOrchestrator

    let loginHelper: IosLoginHelper
    let appHelper: IosAppHelper
    let libraryHelper: IosLibraryHelper
    let playbackHelper: IosPlaybackHelper

    init() {
        let dataStore = IosFactories.shared.createAppSettingsDataStore()
        settingsRepository = AppSettingsRepository(dataStore: dataStore)
        sessionRepository = IosSessionRepository()
        database = IosFactories.shared.buildSonnetDatabase()

        let engine = IosHttpClientKt.createDarwinEngine()
        sonnetApiClient = SonnetApiClient(engine: engine)
        authApiClient = AuthApiClient(apiClient: sonnetApiClient)
        let rawBooksApiClient = BooksApiClient(apiClient: sonnetApiClient)
        authSessionManager = AuthSessionManager(
            sessionStore: sessionRepository,
            authRemoteDataSource: authApiClient
        )
        booksApiClient = AuthenticatedBooksApiClient(
            booksApiClient: rawBooksApiClient,
            authSessionManager: authSessionManager
        )

        let booksDir = IosFactories.shared.libraryDownloadDirectory()
        libraryRepository = LibraryRepository(
            booksApiClient: booksApiClient,
            libraryDao: database.libraryDao(),
            booksDirectory: booksDir,
            fileSystem: FileSystem.SYSTEM
        )

        platformAuthProvider = IosPlatformAuthProvider()

        loginRepository = LoginRepository(
            authApiClient: authApiClient,
            authSessionManager: authSessionManager,
            settingsRepository: settingsRepository,
            localLibraryCleaner: libraryRepository,
            enforcesHttps: false
        )

        let progressSyncer = ProgressSyncer(
            remoteDataSource: booksApiClient,
            playbackProgressDao: database.libraryDao()
        )

        let networkMonitor = IosNetworkMonitor()
        let syncCoordinator = SharedSyncCoordinator(
            networkMonitor: networkMonitor,
            progressSyncer: progressSyncer
        )

        let playbackEngine = IosPlaybackEngine()
        playbackOrchestrator = PlaybackOrchestrator(
            engine: playbackEngine,
            libraryRepository: libraryRepository,
            libraryDao: database.libraryDao(),
            progressSyncer: progressSyncer
        )

        loginHelper = IosLoginHelper(
            loginRepository: loginRepository,
            platformAuthProvider: platformAuthProvider
        )
        appHelper = IosAppHelper(
            repository: loginRepository,
            authSessionManager: authSessionManager
        )
        libraryHelper = IosLibraryHelper(repository: libraryRepository)
        playbackHelper = IosPlaybackHelper(orchestrator: playbackOrchestrator)
    }

    func dispose() {
        playbackOrchestrator.shutdown()
        sonnetApiClient.close()
        platformAuthProvider.dispose()
        database.close()
    }
}
