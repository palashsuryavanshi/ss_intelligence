package com.ssintelligence.app.indexing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.autonomous.AutonomousAnalysisEngine
import com.ssintelligence.app.data.database.ScreenshotEntity
import com.ssintelligence.app.data.database.toDomain
import com.ssintelligence.app.semantic.ScreenshotDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Runs the Phase 6 autonomous analysis over screenshots that need it (§37).
 *
 * Incremental by construction: it selects only screenshots with no topic row yet,
 * so a re-run after an interruption resumes where it stopped rather than
 * reprocessing the whole library. Battery-aware: the catch-up honours the
 * processing mode, and the chunk size is bounded.
 *
 * The worker only ever writes derived intelligence — topics, sessions, events,
 * tags, suggestions, archive state. It never modifies or deletes a screenshot.
 */
class AutonomousAnalysisWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private var iterations = 0

    override suspend fun doWork(): Result {
        val locator = ServiceLocator.from(applicationContext)
        val dao = locator.database.screenshotDao()
        val autonomous = locator.autonomousRepository
        val graph = locator.graphRepository
        val visualDao = locator.database.visualDao()

        var processed = 0
        var offset = 0
        while (iterations < MAX_ITERATIONS) {
            iterations++
            if (isStopped) return Result.retry()
            val batch = dao.rowsWithoutTopic(offset, CHUNK)
            if (batch.isEmpty()) break
            for (row in batch) {
                if (isStopped) return Result.retry()
                runCatching { analyze(row, dao, graph, visualDao, autonomous) }
                    .onFailure { com.ssintelligence.app.util.AppLog.w(TAG, "Autonomous analysis failed screenshotId=${row.id}") }
                processed++
            }
            offset += batch.size
            setProgress(workDataOf(PROGRESS_DONE to processed))
        }
        com.ssintelligence.app.util.AppLog.i(TAG, "Autonomous analysis complete processed=$processed")
        return Result.success()
    }

    private suspend fun analyze(
        row: ScreenshotEntity,
        dao: com.ssintelligence.app.data.database.ScreenshotDao,
        graph: com.ssintelligence.app.graph.GraphRepository,
        visualDao: com.ssintelligence.app.data.database.VisualDao,
        autonomous: com.ssintelligence.app.autonomous.AutonomousRepository,
    ) {
        val urls = dao.urlsForScreenshot(row.id)
        val prices = dao.pricesForScreenshot(row.id)
        val dates = dao.datesForScreenshot(row.id)
        val phones = dao.phonesForScreenshot(row.id)
        val otps = dao.otpsForScreenshot(row.id)
        val visual = visualDao.visualFor(row.id)
        val labels = graph.entityLabelsFor(listOf(row.id))[row.id].orEmpty()
        val document = ScreenshotDocument(
            screenshotId = row.id,
            ocrText = row.ocrText,
            filename = row.filename,
            hosts = urls.map { it.host },
            prices = prices.map {
                com.ssintelligence.app.domain.model.ExtractedPrice(it.id, it.screenshotId, it.rawText, it.currency, it.amount)
            },
            dateTexts = dates.map { it.rawText },
            phoneCount = phones.size,
            otpCount = otps.size,
            dateAdded = row.dateAdded,
        )
        // Neighbours: recent screenshots sharing the library, for session and
        // event detection. Bounded so one row never scans the whole library.
        val neighbours = dao.recentRows(NEIGHBOUR_SCAN)
            .filter { it.id != row.id }
            .map { other ->
                AutonomousAnalysisEngine.Input.Neighbour(
                    screenshot = other.toDomain(),
                    entities = graph.entityLabelsFor(listOf(other.id))[other.id].orEmpty(),
                    hosts = dao.urlsForScreenshot(other.id).map { it.host },
                    visualType = visualDao.visualFor(other.id)?.shotType,
                )
            }
        autonomous.analyze(
            screenshot = row.toDomain(),
            document = document,
            entities = labels,
            visualType = visual?.shotType,
            visualLayout = visual?.layout,
            isDark = visual?.isDark ?: false,
            colors = visual?.colors?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            neighbours = neighbours,
        )
    }

    companion object {
        const val TAG = "AutonomousAnalysisWorker"
        const val WORK_NAME = "autonomous-analysis"
        const val PROGRESS_DONE = "progress_done"
        const val CHUNK = 50
        const val MAX_ITERATIONS = 10_000
        const val NEIGHBOUR_SCAN = 300

        fun requestNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutonomousAnalysisWorker>()
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun observeProgress(context: Context): Flow<Int> =
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .map { infos -> infos.firstOrNull()?.progress?.getInt(PROGRESS_DONE, 0) ?: 0 }
    }
}
