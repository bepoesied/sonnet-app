package pw.kmr.sonnet.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import pw.kmr.sonnet.SonnetApplication
import pw.kmr.sonnet.shared.sync.BackgroundSyncScheduler
import java.util.concurrent.TimeUnit

class ProgressSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? SonnetApplication)?.appContainer
            ?: return Result.failure()

        if (container.authSessionManager.storedSession() == null) {
            container.authSessionManager.loadStoredSession()
        }
        if (container.authSessionManager.storedSession() == null ||
            container.authSessionManager.requiresLogin.value
        ) {
            return Result.success()
        }

        val syncResult = container.progressSyncer.syncPending()
        return when {
            syncResult.authFailed -> Result.success()
            syncResult.hasTransientFailures -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sonnet_progress_sync"
    }
}

class AndroidBackgroundSyncScheduler(
    context: Context
) : BackgroundSyncScheduler {
    private val applicationContext = context.applicationContext

    override fun scheduleProgressSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ProgressSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
