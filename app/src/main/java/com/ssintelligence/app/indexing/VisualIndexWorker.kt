package com.ssintelligence.app.indexing

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.data.database.ScreenshotVisualEntity
import com.ssintelligence.app.domain.model.OcrLevel
import com.ssintelligence.app.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Backfills visual analysis for screenshots indexed before Phase 4 (§39, §40).
 *
 * Reads each stale row's image at ~192px, computes the hash, palette, type and
 * layout, and files its graph footprint from the extraction tables. Resumable
 * in chunks with the same circuit breaker as the semantic worker: a rebuild
 * that makes no progress stops visibly instead of draining the battery.
 *
 * Like all derived data, failures are isolated per screenshot and never touch
 * the core index (§47).
 */
class VisualIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val locator = ServiceLocator.from(applicationContext)
        val dao = locator.database.screenshotDao()
        val visualDao = locator.database.visualDao()
        val analyzer = locator.visualAnalyzer
        val graph = locator.graphRepository
        val semantic = locator.semanticRepository

        var lastIds: List<Long> = emptyList()
        var iterations = 0
        var done = 0
        while (iterations < MAX_ITERATIONS) {
            iterations++
            if (isStopped) return Result.retry()
            val ids = visualDao.staleVisualIds(
                com.ssintelligence.app.vision.VisualAnalysis.MODEL_VERSION,
                REBUILD_CHUNK,
            )
            if (ids.isEmpty()) break
            if (ids == lastIds) {
                AppLog.w(TAG, "Visual rebuild stalled on ${ids.size} ids; stopping")
                break
            }
            lastIds = ids
            for (row in dao.getByIds(ids)) {
                if (isStopped) return Result.retry()
                runCatching {
                    val uri = Uri.parse(row.uri)
                    val blocks = dao.blocksForScreenshot(row.id).map { block ->
                        com.ssintelligence.app.domain.model.OcrBlock(
                            level = com.ssintelligence.app.domain.model.OcrLevel.LINE,
                            text = block.text,
                            left = block.left,
                            top = block.top,
                            right = block.right,
                            bottom = block.bottom,
                            confidence = block.confidence,
                        )
                    }
                    val analysis = analyzer.analyze(uri, blocks, row.width, row.height)
                    visualDao.upsertVisual(
                        ScreenshotVisualEntity(
                            screenshotId = row.id,
                            dhash = analysis.imageHash,
                            colors = analysis.colors.joinToString(","),
                            brightness = analysis.brightness,
                            isDark = analysis.isDark,
                            textCoverage = analysis.textCoverage,
                            shotType = analysis.type.name,
                            layout = analysis.layout.name,
                            modelVersion = analysis.modelVersion,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    // Graph footprints for pre-Phase-4 rows, filed from the
                    // same extraction tables the live pipeline uses.
                    graph.fileScreenshotFromTables(row.id)
                }.onFailure {
                    AppLog.w(TAG, "Visual rebuild failed screenshotId=${row.id}")
                }
                done++
            }
            setProgress(workDataOf(PROGRESS_DONE to done))
        }
        refillGraphIfEmpty(graph)
        AppLog.i(TAG, "Visual rebuild complete processed=$done")
        return Result.success()
    }

    /**
     * Refills an emptied knowledge graph from the extraction tables.     *
     * "Delete knowledge graph" removes entities and relationships but leaves
     * every screenshot `COMPLETED` with current visuals — so the visual staleness
     * query reports nothing to do, and the graph would stay empty until a full
     * re-index. That is a recovery path the user should not have to pay for.
     *
     * Only a completely empty graph triggers this. A partially filled one is
     * left alone, because "no relations" is a legitimate state for a screenshot
     * with nothing extractable in it.
     */
    private suspend fun refillGraphIfEmpty(
        graph: com.ssintelligence.app.graph.GraphRepository,
    ) {
        val locator = ServiceLocator.from(applicationContext)
        if (locator.database.graphDao().relationCount() > 0) return
        val completed = locator.database.screenshotDao().recentRows(GRAPH_SCAN)
        if (completed.isEmpty()) return
        AppLog.i(TAG, "Graph is empty; refilling from ${completed.size} screenshots")
        for (row in completed) {
            if (isStopped) return
            runCatching { graph.fileScreenshotFromTables(row.id) }
                .onFailure { AppLog.w(TAG, "Graph refill failed screenshotId=${row.id}") }
        }
        setProgress(workDataOf(PROGRESS_GRAPH to completed.size))
    }

    companion object {
        const val WORK_NAME = "visual-index-rebuild"
        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_GRAPH = "progress_graph"
        const val REBUILD_CHUNK = 100
        const val MAX_ITERATIONS = 10_000

        /** Bound on the emptied-graph refill, matching the near-duplicate scan. */
        const val GRAPH_SCAN = 5000

        fun requestNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<VisualIndexWorker>()
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Mode-aware catch-up, mirroring the semantic worker (§62). */
        fun requestCatchUp(
            context: Context,
            mode: com.ssintelligence.app.domain.repository.ProcessingMode,
        ) {
            val builder = OneTimeWorkRequestBuilder<VisualIndexWorker>()
                .addTag(WORK_NAME)
            if (mode == com.ssintelligence.app.domain.repository.ProcessingMode.MANUAL) return
            if (mode == com.ssintelligence.app.domain.repository.ProcessingMode.CHARGING_ONLY) {
                builder.setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
            }
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, builder.build())
        }

        fun observeProgress(context: Context): Flow<Int> =
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .map { infos ->
                    infos.firstOrNull()?.progress?.getInt(PROGRESS_DONE, 0) ?: 0
                }
    }
}

private const val TAG = "VisualIndexWorker"
