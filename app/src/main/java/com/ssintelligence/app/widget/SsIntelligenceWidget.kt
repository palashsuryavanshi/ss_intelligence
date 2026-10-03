package com.ssintelligence.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ssintelligence.app.MainActivity
import com.ssintelligence.app.R
import com.ssintelligence.app.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home screen widget (§53).
 *
 * Shows the count of indexed screenshots and a tap-to-open shortcut.
 * No sensitive content is displayed — only counts and a generic label.
 */
class SsIntelligenceWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_ss_intelligence)

            // Open the app when tapped
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            // Refresh count asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                val locator = ServiceLocator.from(context.applicationContext)
                val count = try {
                    locator.screenshotRepository.countByStatus().values.sum()
                } catch (_: Exception) {
                    0
                }
                val pending = try {
                    locator.screenshotRepository.pendingCount()
                } catch (_: Exception) {
                    0
                }
                views.setTextViewText(R.id.widget_count, "$count screenshots indexed")
                views.setTextViewText(
                    R.id.widget_subtitle,
                    if (pending > 0) "$pending pending" else "Tap to open",
                )
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}