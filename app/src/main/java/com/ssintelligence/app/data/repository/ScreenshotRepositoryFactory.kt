package com.ssintelligence.app.data.repository

import android.content.Context
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.domain.repository.ScreenshotRepository

/** Factory for the Room-backed screenshot index. */
object ScreenshotRepositoryFactory {
    fun create(context: Context, database: SsIntelligenceDatabase): ScreenshotRepository =
        ScreenshotRepositoryImpl(
            context = context.applicationContext,
            database = database,
            dao = database.screenshotDao(),
        )
}
