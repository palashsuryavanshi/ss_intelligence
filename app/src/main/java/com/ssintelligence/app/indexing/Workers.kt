package com.ssintelligence.app.indexing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.IndexingScope
import com.ssintelligence.app.util.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Scans MediaStore for screenshots and reconciles the index (§7, §8).
 *
 * The scan itself reads metadata only — no image is decoded — so it finishes
 * quickly even for tens of thousands of screenshots and never blocks the UI.
 */
class DiscoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val locator = ServiceLocator.from(applicationContext)
        return try {
            // Read the scope from DataStore rather than a cached value so a
            // freshly started process never scans with stale settings.
            val scope = locator.settingsRepository.observeScope().first()
            val images = locator.screenshotSource.discoverImages(
                includeAllImages = scope == IndexingScope.ALL_IMAGES,
            )
            val result = locator.screenshotRepository.applyDiscovery(images)
            AppLog.i(
                TAG,
                "Discovery finished found=${images.size} added=${result.added} " +
                    "updated=${result.updated} removed=${result.removed}",
            )
            Result.success(
                Data.Builder()
                    .putInt(KEY_FOUND, images.size)
                    .putInt(KEY_ADDED, result.added)
                    .putInt(KEY_UPDATED, result.updated)
                    .putInt(KEY_REMOVED, result.removed)
                    .build()
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Includes MediaAccessDeniedException: a permission prompt is a
            // user action, so surface it as a retryable failure rather than
            // silently pretending the scan found nothing.
            AppLog.w(TAG, "Discovery failed: ${e::class.simpleName}")
            Result.retry()
        }
    }

    companion object {
        const val KEY_FOUND = "found"
        const val KEY_ADDED = "added"
        const val KEY_UPDATED = "updated"
        const val KEY_REMOVED = "removed"
        private const val TAG = "ScreenshotIndexer"
    }
}

/**
 * Processes queued screenshots (§20, §21).
 *
 * Properties that matter here:
 * - Survives process death: rows stuck in PROCESSING are re-queued on start.
 * - Bounded resource use: a small batch per execution window and a semaphore
 *   that keeps at most [MAX_PARALLEL] images in memory at once, which avoids
 *   RAM spikes and CPU/battery drain.
 * - Cancellable: `isStopped` is checked between batches, and the user can stop
 *   indexing from Settings.
 * - Offline by design: the only WorkManager constraint is
 *   `NetworkType.NOT_REQUIRED`.
 */
class IndexingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val locator = ServiceLocator.from(applicationContext)
        val repository = locator.screenshotRepository
        val processor = locator.screenshotProcessor

        // Recover rows abandoned by a previous process before claiming new work.
        repository.requeueStaleProcessing()

        val total = repository.pendingCount()
        if (total == 0) {
            AppLog.i(TAG, "No pending screenshots; indexing complete")
            return Result.success(Data.Builder().putInt(KEY_TOTAL, 0).putInt(KEY_DONE, 0).build())
        }
        setProgress(progressData(total, 0))

        var processed = 0
        var failed = 0
        val semaphore = Semaphore(MAX_PARALLEL)

        outer@ while (true) {
            if (isStopped) {
                AppLog.i(TAG, "Indexing stopped after $processed screenshots")
                return Result.failure()
            }
            val batch = repository.nextPending(BATCH_SIZE)
            if (batch.isEmpty()) break

            val outcomes = coroutineScope {
                batch.map { screenshot ->
                    async {
                        semaphore.withPermit {
                            // One bad screenshot must never abort the batch.
                            runCatching { processor.process(screenshot) }
                        }
                    }
                }.awaitAll()
            }

            processed += batch.size
            failed += outcomes.count { it.getOrDefault(false) == false }
            setProgress(progressData(total, processed))

            if (batch.size < BATCH_SIZE) break
            if (isStopped) break@outer
        }

        val remaining = repository.pendingCount()
        AppLog.i(
            TAG,
            "Indexing run finished processed=$processed failed=$failed remaining=$remaining",
        )
        return if (remaining > 0) Result.retry() else Result.success(
            Data.Builder()
                .putInt(KEY_TOTAL, total)
                .putInt(KEY_DONE, processed)
                .putInt(KEY_FAILED, failed)
                .build()
        )
    }

    private fun progressData(total: Int, done: Int): Data = Data.Builder()
        .putInt(KEY_TOTAL, total)
        .putInt(KEY_DONE, done)
        .build()

    companion object {
        const val KEY_TOTAL = "total"
        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"

        /** Screenshots processed per batch before yielding back to the system. */
        const val BATCH_SIZE = 12

        /** Simultaneous decodes. Keeps peak memory bounded (§21, §31). */
        const val MAX_PARALLEL = 2

        private const val TAG = "ScreenshotIndexer"
    }
}
