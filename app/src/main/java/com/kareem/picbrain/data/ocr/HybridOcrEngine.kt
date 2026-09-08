package com.kareem.picbrain.data.ocr

import android.content.Context
import android.net.Uri

class HybridOcrEngine(context: Context) : OcrEngine {
    override val id: String = ID

    private val latin = MlKitOcrEngine(context)
    private val arabic = TesseractArabicOcrEngine(context)

    override suspend fun recognize(uri: Uri): OcrResult {
        val latinResult = runCatching { latin.recognize(uri) }
        val arabicResult = runCatching { arabic.recognize(uri) }

        if (latinResult.isFailure && arabicResult.isFailure) {
            val latinError = latinResult.exceptionOrNull()?.message.orEmpty()
            val arabicError = arabicResult.exceptionOrNull()?.message.orEmpty()
            error("Both OCR engines failed. Latin: $latinError; Arabic: $arabicError")
        }

        val merged = mergeDistinctText(
            latinResult.getOrNull()?.rawText.orEmpty(),
            arabicResult.getOrNull()?.rawText.orEmpty()
        )
        return OcrResult(
            rawText = merged,
            normalizedText = normalizeOcrText(merged)
        )
    }

    override fun close() {
        latin.close()
        arabic.close()
    }

    private fun mergeDistinctText(latinText: String, arabicText: String): String {
        val seen = linkedSetOf<String>()
        return sequenceOf(latinText, arabicText)
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter { line -> seen.add(normalizeOcrText(line)) }
            .joinToString("\n")
    }

    companion object {
        const val ID = "hybrid-mlkit16.0.1-tesseract5.5.1-ara-fast-v1"
    }
}
