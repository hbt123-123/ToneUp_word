package top.toneup.word.work

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.time.SystemTimeProvider
import top.toneup.word.data.gateway.GatewayClientProvider
import top.toneup.word.data.gateway.GatewayDefaults
import top.toneup.word.data.gateway.MeaningLookupService
import top.toneup.word.data.gateway.toGatewayConfig
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.repository.DefaultMeaningSyncRepository
import top.toneup.word.data.settings.DataStoreSettingsRepository

/**
 * Batch meaning completion (plan §7.3).
 *
 * Hilt-free by design, exactly like [StudyReminderWorker]: the worker builds its own object
 * graph from the application context (database, DataStore settings, gateway client), runs one
 * throttled batch of the cascade and closes the database again.
 *
 * Results:
 *  * `success` with `processed` = how many words were attempted;
 *  * `failure` when the gateway rejected the configuration (HTTP 401 / bad URL) — the whole
 *    unique queue stops instead of burning the daily quota with a wrong key;
 *  * `retry` on any unexpected crash, with WorkManager's exponential backoff.
 */
class MeaningSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val database = ToneUpWordDatabase.build(applicationContext)
        val settingsRepository = DataStoreSettingsRepository(applicationContext)
        return try {
            val time = SystemTimeProvider()
            val debugLogging = (applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val provider = GatewayClientProvider(
                configProvider = { settingsRepository.current().toGatewayConfig() },
                isDebugBuild = debugLogging,
            )
            val service = MeaningLookupService(
                clients = provider,
                cacheDao = database.meaningCacheDao(),
                wordDao = database.wordDao(),
                time = time,
                json = JsonCodec.json,
            )
            val repository = DefaultMeaningSyncRepository(
                context = applicationContext,
                db = database,
                time = time,
                lookupService = service,
                settingsRepository = settingsRepository,
                json = JsonCodec.json,
            )
            val report = repository.runBatch(GatewayDefaults.BATCH_LIMIT)
            val output = workDataOf(KEY_PROCESSED to report.processed)
            if (report.abortedByConfig) Result.failure(output) else Result.success(output)
        } catch (_: Throwable) {
            // A worker must never crash the app; back off and try again later.
            Result.retry()
        } finally {
            database.close()
        }
    }

    companion object {
        /** Key of the `processed` counter in the result data. */
        const val KEY_PROCESSED = "processed"
    }
}
