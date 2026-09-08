package com.kareem.picbrain.data.ocr

import android.net.Uri

interface OcrEngine {
    val id: String
    suspend fun recognize(uri: Uri): OcrResult
}

data class OcrResult(
    val rawText: String,
    val normalizedText: String
)

fun normalizeOcrText(text: String): String = text
    .replace(Regex("\\s+"), " ")
    .trim()
    .lowercase()
