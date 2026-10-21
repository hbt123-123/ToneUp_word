package top.toneup.word.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import top.toneup.word.data.repository.AppUpdateFactory
import top.toneup.word.data.repository.AppUpdateRepository
import top.toneup.word.data.update.AppUpdateCheckResult

/**
 * Silent check + resumable download of the newest GitHub release (in-app update, plan: 版本更新).
 *
 * Hilt-free like every worker here: it builds the small update graph from the application
 * context. The unique work is enqueued on each app start ([enqueueCheckOnLaunch]); network is
 * the only constraint, so metered connections are used too — the download is silent but it is
 * also the only way updates reach users who never use Wi-Fi.
 *
 * Result mapping:
 *  * success — up-to-date, suppressed, or downloaded (the prompt flow picks it up);
 *  * retry — retryable failure (timeouts, 5xx, broken mid-stream) with exponential backoff;
 *    the partial file is the resume state, so a retry only fetches the missing tail;
 *  * failure — permanent problems (bad release layout, HTTP 404: no release published yet).
 */
class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val repository: AppUpdateRepository = AppUpdateFactory.create(applicationContext)
        when (val outcome = repository.checkAndDownload()) {
            is AppUpdateCheckResult.Failed ->
                if (outcome.error.retryable) Result.retry() else Result.failure()

            else -> Result.success()
        }
    } catch (_: Throwable) {
        // A worker must never crash the app; back off and try again later.
        Result.retry()
    }
}
