package com.kareem.picbrain.data.ocr

import android.net.Uri
import java.text.Normalizer
import java.util.Locale

interface OcrEngine {
    val id: String
    suspend fun recognize(uri: Uri): OcrResult
    fun close() = Unit
}

data class OcrResult(
    val rawText: String,
    val normalizedText: String
)

private val arabicDiacritics = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
private val nonSearchCharacters = Regex("[^\\p{L}\\p{N}]+")
private val whitespace = Regex("\\s+")

fun normalizeOcrText(text: String): String {
    if (text.isBlank()) return ""

    return Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace("ـ", "")
        .replace(arabicDiacritics, "")
        .map(::normalizeSearchChar)
        .joinToString(separator = "")
        .replace(nonSearchCharacters, " ")
        .replace(whitespace, " ")
        .trim()
}

private fun normalizeSearchChar(char: Char): Char = when (char) {
    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
    'ى', 'ئ' -> 'ي'
    'ؤ' -> 'و'
    '٠', '۰' -> '0'
    '١', '۱' -> '1'
    '٢', '۲' -> '2'
    '٣', '۳' -> '3'
    '٤', '۴' -> '4'
    '٥', '۵' -> '5'
    '٦', '۶' -> '6'
    '٧', '۷' -> '7'
    '٨', '۸' -> '8'
    '٩', '۹' -> '9'
    else -> char
}
