package com.ssintelligence.app.indexing

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Owns all WorkManager scheduling for indexing (§21).
 *
 * UI state is derived from the database (the source of truth, and correct even
 * after process death) rather than from worker progress data, so progress is
 * never lost when the app is restarted mid-run.
 */
class IndexingScheduler(
    context: Context,
    private val workManager: WorkManager,
) {
    /** Enqueues a full scan + process run, replacing any run in progress. */
    fun requestIndexing(): Operation = workManager
        .beginUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, discoveryRequest())
        .then(indexingRequest())
        .enqueue()

    /** Resumes processing without rescanning MediaStore. */
    fun requestProcessingOnly(): Operation = workManager
        .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, indexingRequest())

    /** User-triggered cancellation. Screenshots already committed stay indexed. */
    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK)
    }

    fun observeWorkState(): Flow<WorkInfo.State?> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK)
            .map { infos -> infos.firstOrNull()?.state }

    /**
     * Discovery is a metadata-only scan, so it can run expedited to make
     * first launch feel instant. Expedited work may only declare network and
     * storage constraints, so it uses [expeditedConstraints] rather than
     * [baseConstraints] (which adds a battery guard).
     */
    private fun discoveryRequest() = OneTimeWorkRequestBuilder<DiscoveryWorker>()
        .setConstraints(expeditedConstraints)
        .addTag(TAG)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    private fun indexingRequest() = OneTimeWorkRequestBuilder<IndexingWorker>()
        .setConstraints(baseConstraints)
        .addTag(TAG)
        .setBackoffCriteria(
            androidx.work.BackoffPolicy.LINEAR,
            MIN_BACKOFF_SECONDS,
            TimeUnit.SECONDS,
        )
        .build()

    /**
     * Indexing is CPU/disk bound and must work with the network off, so no
     * network constraint is declared (§2, §41). `requiresBatteryNotLow` is the
     * one guard rail: it stops a long OCR run from draining a nearly-empty
     * battery.
     */
    private val baseConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    /**
     * WorkManager rejects expedited requests that declare device-state
     * constraints such as `requiresBatteryNotLow`, so the expedited discovery
     * scan is limited to the network/storage types it permits.
     */
    private val expeditedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresStorageNotLow(true)
        .build()

    companion object {
        const val UNIQUE_WORK = "ss_intelligence_indexing"
        const val TAG = "indexing"
        private const val MIN_BACKOFF_SECONDS = 10L
    }
}
