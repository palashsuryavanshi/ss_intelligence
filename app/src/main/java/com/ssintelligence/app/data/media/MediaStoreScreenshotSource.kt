package com.ssintelligence.app.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.ssintelligence.app.domain.model.MediaImage
import com.ssintelligence.app.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * MediaStore-backed screenshot discovery (§6).
 *
 * Only the `content://` URI is retained — never a filesystem path — because
 * MediaStore IDs are the stable identity on Android 10+ and file paths change
 * after moves, edits and cloud sync.
 *
 * Incremental support (§8): a full cursor scan is cheap (metadata only, no
 * image decoding) and the repository reconciles the result against the index
 * by [MediaImage.mediaStoreId], so unchanged screenshots are never re-OCR'd.
 */
class MediaStoreScreenshotSource(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : ScreenshotSource {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun discoverImages(includeAllImages: Boolean): List<MediaImage> =
        withContext(ioDispatcher) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.MIME_TYPE,
            )

            val results = mutableListOf<MediaImage>()
            var scanned = 0
            // No SQL-level filter on SIZE/DATE: MediaStore rows can be
            // incompletely populated (NULL size or dimensions) after a device
            // restore, a media-provider reset or an interrupted scan. Filtering
            // in SQL would silently drop those screenshots, so the decision is
            // made in Kotlin from metadata that is actually present.
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val cursorOrNull = resolver.query(collection, projection, null, null, sortOrder)
            if (cursorOrNull == null) {
                AppLog.w(TAG, "MediaStore returned no cursor for $collection")
            }
            try {
                cursorOrNull?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val mimeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                    while (cursor.moveToNext()) {
                        // Support cancellation so a "Scan now" can be aborted.
                        try {
                            ensureActive()
                        } catch (e: CancellationException) {
                            AppLog.i(TAG, "Discovery cancelled after ${results.size} rows")
                            return@use
                        }

                        val id = cursor.getLong(idColumn)
                        val filename = cursor.getString(nameColumn) ?: continue
                        val relativePath = if (pathColumn >= 0) cursor.getString(pathColumn) else null
                        val mime = if (mimeColumn >= 0) cursor.getString(mimeColumn) else null
                        val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
                        val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0

                        val isScreenshot = ScreenshotHeuristics.looksLikeScreenshot(filename, relativePath)
                        scanned++
                        if (!includeAllImages && !isScreenshot) continue

                        // Missing width/height is *not* grounds for skipping:
                        // MediaStore rows can be incompletely populated right
                        // after a device restore or a provider rescan, and OCR
                        // can still decode such an image. Genuinely unreadable
                        // images are handled per-row by the processor, which
                        // marks them FAILED instead of dropping them silently.
                        results += MediaImage(
                            mediaStoreId = id,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            filename = filename,
                            relativePath = relativePath?.trimEnd('/'),
                            dateAddedSec = cursor.getLong(addedColumn),
                            dateModifiedSec = cursor.getLong(modifiedColumn),
                            // NULL columns read as 0; the image itself is the
                            // authority on whether these values are usable.
                            sizeBytes = cursor.getLong(sizeColumn),
                            width = width,
                            height = height,
                            mimeType = mime,
                            isLikelyScreenshot = isScreenshot,
                        )
                    }
                }
            } catch (e: SecurityException) {
                AppLog.w(TAG, "Media access denied while discovering screenshots")
                throw MediaAccessDeniedException(e)
            } catch (e: Exception) {
                // Includes IllegalStateException / IllegalArgumentException from
                // a provider that cannot serve the full projection.
                AppLog.e(TAG, "MediaStore query failed scanned=$scanned", e)
                throw MediaAccessDeniedException(e)
            }

            AppLog.i(TAG, "Discovery complete: scanned=$scanned candidates=${results.size}")
            results
        }

    /** Best-effort liveness probe for a stored URI (§30). */
    suspend fun exists(uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** API level where RELATIVE_PATH / WIDTH / HEIGHT are reliable. */
        val MIN_WIDTH_HEIGHT_API: Int = Build.VERSION_CODES.Q

        private const val TAG = "ScreenshotIndexer"
    }
}

class MediaAccessDeniedException(cause: Throwable? = null) :
    Exception("Screenshot access denied", cause)
