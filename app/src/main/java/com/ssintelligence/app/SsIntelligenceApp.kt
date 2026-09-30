package com.ssintelligence.app

import android.app.Application
import androidx.work.Configuration
import com.ssintelligence.app.util.AppLog

/**
 * Application entry point.
 *
 * Installs the dependency container and configures on-demand WorkManager
 * initialization so a database is never opened just because the process
 * started.
 */
class SsIntelligenceApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.install(this)
        AppLog.i(TAG, "Application started")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private companion object {
        const val TAG = "SSIntelligence"
    }
}
