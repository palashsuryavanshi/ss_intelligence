package com.ssintelligence.app

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.data.media.MediaStoreScreenshotSource
import com.ssintelligence.app.data.media.ScreenshotSource
import com.ssintelligence.app.data.repository.SearchHistoryRepositoryImpl
import com.ssintelligence.app.data.repository.ScreenshotRepositoryFactory
import com.ssintelligence.app.data.repository.SemanticRepositoryImpl
import com.ssintelligence.app.data.repository.SettingsRepositoryImpl
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import com.ssintelligence.app.domain.repository.SettingsRepository
import com.ssintelligence.app.duplicate.ContentHashDetector
import com.ssintelligence.app.duplicate.ImageSimilarityDetector
import com.ssintelligence.app.graph.GraphRepository
import com.ssintelligence.app.graph.GraphRepositoryImpl
import com.ssintelligence.app.indexing.IndexingScheduler
import com.ssintelligence.app.indexing.ScreenshotProcessor
import com.ssintelligence.app.ml.extract.MetadataExtractor
import com.ssintelligence.app.ml.ocr.MlKitTextRecognizer
import com.ssintelligence.app.ml.ocr.TextRecognizer
import com.ssintelligence.app.search.LocalSearchEngine
import com.ssintelligence.app.search.ScreenshotSearchEngine
import com.ssintelligence.app.semantic.HashedNgramEmbeddingProvider
import com.ssintelligence.app.semantic.SemanticRepository
import com.ssintelligence.app.vision.BitmapVisualAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** Exposed for workers and system services that need a Context. */
    fun toApplicationContext(): Context = appContext

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

    val visualAnalyzer: BitmapVisualAnalyzer by lazy {
        BitmapVisualAnalyzer(appContext.contentResolver, Dispatchers.IO)
    }

    val graphRepository: GraphRepository by lazy {
        GraphRepositoryImpl(database.graphDao(), database.screenshotDao(), database.semanticDao())
    }

    val searchEngine: ScreenshotSearchEngine by lazy {
        LocalSearchEngine(
            dao = database.screenshotDao(),
            history = searchHistoryRepository,
            semanticRepository = semanticRepository,
            semanticSettings = settingsRepository,
            visualDao = database.visualDao(),
            graphRepository = graphRepository,
        )
    }

    /**
     * Local semantic index (§9 Phase 3).
     *
     * Built in, not downloaded: the embedding provider needs no model file, so
     * semantic search works the moment the library is indexed. A neural
     * provider can replace [HashedNgramEmbeddingProvider] here without
     * touching anything downstream.
     */
    val semanticRepository: SemanticRepository by lazy {
        SemanticRepositoryImpl(
            database = database,
            dao = database.screenshotDao(),
            semanticDao = database.semanticDao(),
            provider = HashedNgramEmbeddingProvider(),
        ).also { semantic ->
            // Breaks the circular dependency: the repository calls into the
            // semantic index on save, and the semantic index reads the database.
            (screenshotRepository as? com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl)
                ?.semanticRepository = semantic
            (screenshotRepository as? com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl)
                ?.graphRepository = graphRepository
        }
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
            visualAnalyzer = visualAnalyzer,
        )
    }

    val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    val indexingScheduler: IndexingScheduler by lazy {
        IndexingScheduler(appContext, workManager)
    }

    /**
     * One-shot intelligence catch-up after launch (§39, §62).
     *
     * Reads the processing mode once and enqueues the semantic and visual
     * backfills accordingly — or not at all in Manual mode. KEEP policy means
     * repeated launches never stack duplicate work.
     */
    fun requestIntelligenceCatchUp() {
        applicationScope.launch {
            val mode = runCatching { settingsRepository.processingMode() }
                .getOrDefault(
                    com.ssintelligence.app.domain.repository.ProcessingMode.AUTOMATIC,
                )
            com.ssintelligence.app.indexing.SemanticIndexWorker.requestCatchUp(appContext, mode)
            com.ssintelligence.app.indexing.VisualIndexWorker.requestCatchUp(appContext, mode)
        }
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
