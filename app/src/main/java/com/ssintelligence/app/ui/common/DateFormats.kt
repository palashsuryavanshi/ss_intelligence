package com.ssintelligence.app.ui.common

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locale-aware, timezone-correct date formatting for the UI (§23, §24). */
object DateFormats {

    private val dayFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val shortFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    /** MediaStore stores epoch seconds. */
    fun fromEpochSeconds(epochSeconds: Long): LocalDate =
        Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    fun formatDate(epochSeconds: Long): String = dayFormatter.format(fromEpochSeconds(epochSeconds))

    fun formatDayMonth(epochSeconds: Long): String = shortFormatter.format(fromEpochSeconds(epochSeconds))

    fun formatDateTime(epochSeconds: Long): String {
        val zone = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        return "${shortFormatter.format(dateTime)}, ${timeFormatter.format(dateTime)}"
    }

    /** Extracted dates are stored as days since epoch. */
    fun formatEpochDay(epochDay: Long): String =
        dayFormatter.format(LocalDate.ofEpochDay(epochDay))

    fun formatFileSize(context: Context, bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }
}
