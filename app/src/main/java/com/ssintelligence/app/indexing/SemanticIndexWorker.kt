package com.ssintelligence.app.indexing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.data.database.ScreenshotDao
import com.ssintelligence.app.semantic.ScreenshotDocument
import com.ssintelligence.app.semantic.SemanticRepository
import com.ssintelligence.app.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Rebuilds the semantic index (§37).
 *
 * Embeds every screenshot that is missing an embedding or carries one built by
 * an older provider version, in resumable chunks. Progress is reported through
 * WorkManager so Settings can show "Rebuilding semantic index… 2,831 / 10,482".
 *
 * Scheduling policy (§39):
 * - A manual "Build semantic index" tap runs now, on any power state — the
 *   user asked for it.
 * - The automatic catch-up after an upgrade prefers charging and idle, because
 *   embedding thousands of screenshots is the most expensive thing this app
 *   does. It never blocks viewing, searching (lexical works regardless), or
 *   normal indexing.
 */
class SemanticIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val locator = ServiceLocator.from(applicationContext)
        val semantic = locator.semanticRepository
        if (!semantic.isAvailable) {
            AppLog.i(TAG, "Semantic provider unavailable; nothing to build")
            return Result.success()
        }
        val dao: ScreenshotDao = locator.database.screenshotDao()

        // Circuit breaker: if two consecutive chunks return the same ids, no
        // progress is being made (e.g. every row fails to embed) and looping
        // would drain the battery for nothing. Fail visibly instead of
        // spinning forever.
        var lastIds: List<Long> = emptyList()
        var iterations = 0
        var done = 0
        while (iterations < MAX_ITERATIONS) {
            iterations++
            if (isStopped) return Result.retry()
            val ids = semantic.staleIds(REBUILD_CHUNK)
            if (ids.isEmpty()) break
            if (ids == lastIds) {
                AppLog.w(TAG, "Semantic rebuild stalled on ${ids.size} ids; stopping")
                break
            }
            lastIds = ids
            val rows = dao.getByIds(ids)
            for (row in rows) {
                if (isStopped) return Result.retry()
                runCatching {
                    semantic.indexScreenshot(row.toSemanticDocument(locator, dao))
                }.onFailure {
                    AppLog.w(TAG, "Semantic rebuild failed screenshotId=${row.id}")
                }
                done++
            }
            setProgress(workDataOf(PROGRESS_DONE to done))
        }
        AppLog.i(TAG, "Semantic rebuild complete embedded=$done")
        return Result.success()
    }

    private suspend fun com.ssintelligence.app.data.database.ScreenshotEntity.toSemanticDocument(
        locator: ServiceLocator,
        dao: ScreenshotDao,
    ): ScreenshotDocument {
        val urls = dao.urlsForScreenshot(id)
        val prices = dao.pricesForScreenshot(id)
        val dates = dao.datesForScreenshot(id)
        val phones = dao.phonesForScreenshot(id)
        val otps = dao.otpsForScreenshot(id)
        return ScreenshotDocument(
            screenshotId = id,
            ocrText = ocrText,
            filename = filename,
            hosts = urls.map { it.host },
            prices = prices.map {
                com.ssintelligence.app.domain.model.ExtractedPrice(
                    it.id, it.screenshotId, it.rawText, it.currency, it.amount,
                )
            },
            dateTexts = dates.map { it.rawText },
            phoneCount = phones.size,
            otpCount = otps.size,
            dateAdded = dateAdded,
        )
    }

    companion object {
        const val WORK_NAME = "semantic-index-rebuild"
        const val PROGRESS_DONE = "progress_done"

        /** Resumable chunks: a killed process loses at most one chunk's progress. */
        const val REBUILD_CHUNK = 100

        /**
         * Absolute iteration cap. A healthy rebuild needs roughly
         * totalRows / REBUILD_CHUNK passes; anything beyond this is a loop,
         * not a library.
         */
        const val MAX_ITERATIONS = 10_000

        fun requestNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SemanticIndexWorker>()
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Catch-up after an upgrade or a provider change: only while charging
         * and idle, because this is the most expensive job the app runs.
         */
        fun requestQuietCatchUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<SemanticIndexWorker>()
                .addTag(WORK_NAME)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Mode-aware catch-up (§62).
         *
         * Manual means never: only explicit taps run the builder. Charging-only
         * waits for power and idle. Automatic runs soon, unconstrained — the
         * work itself is chunked and resumable, so an interruption costs
         * nothing.
         */
        fun requestCatchUp(
            context: Context,
            mode: com.ssintelligence.app.domain.repository.ProcessingMode,
        ) {
            when (mode) {
                com.ssintelligence.app.domain.repository.ProcessingMode.MANUAL -> Unit
                com.ssintelligence.app.domain.repository.ProcessingMode.CHARGING_ONLY ->
                    requestQuietCatchUp(context)

                com.ssintelligence.app.domain.repository.ProcessingMode.AUTOMATIC -> {
                    val request = OneTimeWorkRequestBuilder<SemanticIndexWorker>()
                        .addTag(WORK_NAME)
                        .build()
                    WorkManager.getInstance(context.applicationContext)
                        .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
                }
            }
        }

        fun observeProgress(context: Context): Flow<Int> =
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .map { infos ->
                    infos.firstOrNull()?.progress?.getInt(PROGRESS_DONE, 0) ?: 0
                }

        fun observeState(context: Context): Flow<WorkInfo.State?> =
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .map { infos -> infos.firstOrNull()?.state }
    }
}

private const val TAG = "SemanticIndexWorker"
