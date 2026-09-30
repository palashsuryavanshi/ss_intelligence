package com.ssintelligence.app

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.data.media.MediaStoreScreenshotSource
import com.ssintelligence.app.data.media.ScreenshotSource
import com.ssintelligence.app.data.repository.SearchHistoryRepositoryImpl
import com.ssintelligence.app.data.repository.ScreenshotRepositoryFactory
import com.ssintelligence.app.data.repository.SettingsRepositoryImpl
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import com.ssintelligence.app.domain.repository.SettingsRepository
import com.ssintelligence.app.duplicate.ContentHashDetector
import com.ssintelligence.app.duplicate.ImageSimilarityDetector
import com.ssintelligence.app.indexing.IndexingScheduler
import com.ssintelligence.app.indexing.ScreenshotProcessor
import com.ssintelligence.app.ml.extract.MetadataExtractor
import com.ssintelligence.app.ml.ocr.MlKitTextRecognizer
import com.ssintelligence.app.ml.ocr.TextRecognizer
import com.ssintelligence.app.search.LocalSearchEngine
import com.ssintelligence.app.search.ScreenshotSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container.
 *
 * A hand-rolled locator is used instead of a DI framework: the graph is small
 * and entirely local, so annotation processing would add build cost without
 * improving maintainability (§3, §45). Members are lazy so a cold start does
 * not open the database until something needs it.
 */
class ServiceLocator private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: SsIntelligenceDatabase by lazy { SsIntelligenceDatabase.build(appContext) }

    val screenshotRepository: ScreenshotRepository by lazy {
        ScreenshotRepositoryFactory.create(appContext, database)
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(appContext) }

    val searchHistoryRepository: SearchHistoryRepository by lazy {
        SearchHistoryRepositoryImpl(database.searchHistoryDao(), settingsRepository)
    }

    val screenshotSource: ScreenshotSource by lazy {
        MediaStoreScreenshotSource(appContext, Dispatchers.IO)
    }

    val searchEngine: ScreenshotSearchEngine by lazy {
        LocalSearchEngine(dao = database.screenshotDao(), history = searchHistoryRepository)
    }

    private val recognizer: TextRecognizer by lazy {
        MlKitTextRecognizer(appContext, appContext.contentResolver, Dispatchers.IO)
    }

    private val similarityDetector: ImageSimilarityDetector by lazy {
        ContentHashDetector(appContext.contentResolver, Dispatchers.IO)
    }

    val screenshotProcessor: ScreenshotProcessor by lazy {
        ScreenshotProcessor(
            repository = screenshotRepository,
            recognizer = recognizer,
            similarityDetector = similarityDetector,
            metadataExtractor = MetadataExtractor(),
            processingDispatcher = Dispatchers.Default,
        )
    }

    val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    val indexingScheduler: IndexingScheduler by lazy {
        IndexingScheduler(appContext, workManager)
    }

    companion object {
        @Volatile
        private var instance: ServiceLocator? = null

        fun install(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }

        fun from(context: Context): ServiceLocator = install(context)
    }
}

/** WorkManager configuration applied on app startup (on-demand initialization). */
internal object WorkManagerConfig {
    /** Kept for documentation; [SsIntelligenceApp] provides the real config. */
    fun builder(): Configuration.Builder = Configuration.Builder()
}
