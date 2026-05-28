    val authRepository = AuthRepository(
        authApiClient = authApiClient,
        authSessionManager = authSessionManager,
        settingsRepository = settingsRepository,
        serverUrlPolicy = serverUrlPolicy,
        localLibraryCleaner = libraryRepository
    )
    )
    val authApiClient = AuthApiClient(sonnetApiClient)
    private val rawBooksApiClient = BooksApiClient(sonnetApiClient)
        authSessionManager = authSessionManager,
        authSessionManager = authSessionManager,
        authSessionManager = authSessionManager
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    val progressSyncer = ProgressSyncer(
        booksApiClient = booksApiClient,
        authApiClient = authApiClient,
        libraryDao = database.libraryDao(),
        sessionRepository = sessionRepository
    )
    val syncCoordinator = SyncCoordinator(
        context = applicationContext,
        progressSyncer = progressSyncer
    )
        libraryDao = database.libraryDao(),
        progressSyncer = progressSyncer
        context = applicationContext,
        libraryRepository = libraryRepository,
        libraryDao = database.libraryDao()
    )
import pw.kmr.sonnet.data.local.MIGRATION_2_3
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        sessionRepository = sessionRepository,
        context = applicationContext,
        okHttpClient = okHttpClient
import pw.kmr.sonnet.data.local.SonnetDatabase
import pw.kmr.sonnet.data.remote.BooksApiClient
import pw.kmr.sonnet.library.LibraryRepository
    val database: SonnetDatabase = Room.databaseBuilder(
        applicationContext,
        SonnetDatabase::class.java,
        "sonnet.db"
    )
        .addMigrations(MIGRATION_1_2)
        .build()
    val booksApiClient = BooksApiClient(okHttpClient)
    val libraryRepository = LibraryRepository(
        booksApiClient = booksApiClient,
        libraryDao = database.libraryDao(),
        sessionRepository = sessionRepository
    )
import pw.kmr.sonnet.auth.AuthRepository
import pw.kmr.sonnet.data.local.LocalDataCleaner
import pw.kmr.sonnet.data.remote.AuthApiClient
    val serverUrlPolicy = ServerUrlPolicy(enforcesHttps = BuildConfig.ENFORCE_HTTPS)
    val authApiClient = AuthApiClient(okHttpClient)
    val localDataCleaner = LocalDataCleaner(applicationContext)
    val authRepository = AuthRepository(
        authApiClient = authApiClient,
        sessionRepository = sessionRepository,
        settingsRepository = settingsRepository,
        serverUrlPolicy = serverUrlPolicy,
        localDataCleaner = localDataCleaner
    )

import android.content.Context
import okhttp3.OkHttpClient
import pw.kmr.sonnet.BuildConfig
import pw.kmr.sonnet.data.preferences.AppSettingsRepository
import pw.kmr.sonnet.data.preferences.SessionRepository
import pw.kmr.sonnet.data.remote.ServerUrlPolicy

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val settingsRepository = AppSettingsRepository(applicationContext)
    val sessionRepository = SessionRepository(applicationContext)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder().build()
    val serverUrlPolicy = ServerUrlPolicy(enforceHttps = BuildConfig.ENFORCE_HTTPS)
}