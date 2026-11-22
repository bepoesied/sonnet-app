package pw.kmr.sonnet.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import pw.kmr.sonnet.R
import pw.kmr.sonnet.SonnetApplication
import pw.kmr.sonnet.shared.auth.AuthRequiredException
import pw.kmr.sonnet.shared.library.BookDownloadScheduler
import pw.kmr.sonnet.shared.remote.ApiException
import java.util.concurrent.TimeUnit

class BookDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val restartInProgress = inputData.getBoolean(KEY_RESTART_IN_PROGRESS, false) || runAttemptCount > 0
        val container = (applicationContext as? SonnetApplication)?.appContainer
            ?: return Result.failure()

        if (container.authSessionManager.storedSession() == null) {
            container.authSessionManager.loadStoredSession()
        }
        if (container.authSessionManager.storedSession() == null ||
            container.authSessionManager.requiresLogin.value
        ) {
            return Result.failure()
        }

        val bookTitle = try {
            container.database.libraryDao().libraryItem(bookId)?.title
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

        try {
            setForeground(createForegroundInfo(bookId, bookTitle))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Foreground promotion can be denied if started from background on API 31+;
            // proceed as a regular worker rather than failing the download.
        }

        return try {
            container.libraryRepository.downloadBook(
                bookId = bookId,
                restartInProgress = restartInProgress
            )
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            when {
                isPermanentDownloadError(exception) -> Result.failure()
                runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val bookId = inputData.getString(KEY_BOOK_ID).orEmpty()
        return createForegroundInfo(bookId = bookId, bookTitle = null)
    }

    private fun createForegroundInfo(bookId: String, bookTitle: String?): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(bookTitle ?: applicationContext.getString(R.string.download_notification_title_default))
            .setContentText(applicationContext.getString(R.string.download_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()

        return ForegroundInfo(
            notificationIdFor(bookId),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun ensureNotificationChannel() {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            ?: return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.download_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = applicationContext.getString(R.string.download_notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun isPermanentDownloadError(exception: Exception): Boolean = when (exception) {
        is AuthRequiredException,
        is IllegalArgumentException -> true
        is ApiException -> exception.statusCode in PERMANENT_HTTP_STATUS_CODES
        else -> false
    }

    private fun notificationIdFor(bookId: String): Int =
        NOTIFICATION_ID_BASE + (bookId.hashCode() and 0x7FFF)

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_RESTART_IN_PROGRESS = "restart_in_progress"
        private const val CHANNEL_ID = "sonnet_downloads"
        private const val NOTIFICATION_ID_BASE = 20_000
        private const val MAX_RETRY_ATTEMPTS = 3
        private val PERMANENT_HTTP_STATUS_CODES = setOf(400, 401, 403, 404, 410, 422)

        fun uniqueWorkName(bookId: String): String = "sonnet_download_$bookId"
    }
}

class AndroidBookDownloadScheduler(
    context: Context
) : BookDownloadScheduler {
    private val applicationContext = context.applicationContext

    override fun enqueueDownload(bookId: String, restartInProgress: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    BookDownloadWorker.KEY_BOOK_ID to bookId,
                    BookDownloadWorker.KEY_RESTART_IN_PROGRESS to restartInProgress
                )
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            BookDownloadWorker.uniqueWorkName(bookId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun cancelDownload(bookId: String) {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(
            BookDownloadWorker.uniqueWorkName(bookId)
        )
    }
}
