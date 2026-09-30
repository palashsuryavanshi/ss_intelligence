package com.ssintelligence.app.ml.ocr

import com.ssintelligence.app.domain.model.OcrBlock
import com.ssintelligence.app.domain.model.OcrLevel

/** Result of a single OCR run. */
data class OcrResult(
    /** Full text, blocks joined by newlines. */
    val text: String,
    /** Blocks and lines with normalized bounding boxes (§12). */
    val blocks: List<OcrBlock>,
)

/**
 * On-device OCR contract (§11). The ML Kit implementation runs fully offline
 * because the model is bundled in the APK, which is what makes the app work
 * with the network disabled.
 */
interface TextRecognizer {
    suspend fun recognize(uri: android.net.Uri): OcrResult
}

/** Raised when an image cannot be decoded or recognized. */
class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause)
