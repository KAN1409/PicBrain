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

private val arabicDiacritics = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
private val punctuation = Regex("[^\\p{L}\\p{N}]+")
private val whitespace = Regex("\\s+")

fun normalizeOcrText(text: String): String = text
    .lowercase()
    .replace('٠', '0')
    .replace('١', '1')
    .replace('٢', '2')
    .replace('٣', '3')
    .replace('٤', '4')
    .replace('٥', '5')
    .replace('٦', '6')
    .replace('٧', '7')
    .replace('٨', '8')
    .replace('٩', '9')
    .replace('۰', '0')
    .replace('۱', '1')
    .replace('۲', '2')
    .replace('۳', '3')
    .replace('۴', '4')
    .replace('۵', '5')
    .replace('۶', '6')
    .replace('۷', '7')
    .replace('۸', '8')
    .replace('۹', '9')
    .replace('أ', 'ا')
    .replace('إ', 'ا')
    .replace('آ', 'ا')
    .replace('ٱ', 'ا')
    .replace('ى', 'ي')
    .replace('ؤ', 'و')
    .replace('ئ', 'ي')
    .replace("ـ", "")
    .replace(arabicDiacritics, "")
    .replace(punctuation, " ")
    .replace(whitespace, " ")
    .trim()
