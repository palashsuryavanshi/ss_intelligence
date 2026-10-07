package com.ssintelligence.app

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.SingletonImageLoader
import com.ssintelligence.app.util.AppLog
import okio.Path.Companion.toOkioPath

/**
 * Application entry point.
 *
 * Installs the dependency container and configures on-demand WorkManager
 * initialization so a database is never opened just because the process
 * started.
 *
 * Also provides the shared Coil image loader with bounded caches: thumbnails
 * must never be allowed to grow into hundreds of megabytes on an 8 GB
 * device. Coil automatically trims on memory pressure; the caps below keep
 * steady-state usage small.
 */
class SsIntelligenceApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.install(this)
        AppLog.i(TAG, "Application started")
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil").toOkioPath())
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private companion object {
        const val TAG = "SSIntelligence"
    }
}
