package com.kareem.picbrain.data.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import cz.adaptech.tesseract4android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class TesseractArabicOcrEngine(
    private val context: Context
) : OcrEngine {
    override val id: String = "tesseract-5.5.1-ara-fast"

    private val mutex = Mutex()
    private var tess: TessBaseAPI? = null

    override suspend fun recognize(uri: Uri): OcrResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val api = getOrCreateApi()
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: error("Unable to decode image for Arabic OCR")

            try {
                api.setImage(bitmap)
                val raw = api.utF8Text.orEmpty()
                val arabicRelevant = retainArabicLines(raw)
                OcrResult(
                    rawText = arabicRelevant,
                    normalizedText = normalizeOcrText(arabicRelevant)
                )
            } finally {
                api.clear()
                bitmap.recycle()
            }
        }
    }

    private fun getOrCreateApi(): TessBaseAPI {
        tess?.let { return it }

        val dataRoot = prepareLanguageData()
        val api = TessBaseAPI()
        check(api.init(dataRoot.absolutePath, LANGUAGE)) {
            "Tesseract failed to initialize Arabic traineddata"
        }
        tess = api
        return api
    }

    private fun prepareLanguageData(): File {
        val root = File(context.filesDir, "tesseract")
        val tessDataDir = File(root, "tessdata")
        val destination = File(tessDataDir, ASSET_FILE_NAME)
        if (destination.isFile && destination.length() > 0L) return root

        check(tessDataDir.exists() || tessDataDir.mkdirs()) {
            "Unable to create Tesseract data directory"
        }

        val temp = File(tessDataDir, "$ASSET_FILE_NAME.tmp")
        context.assets.open("tessdata/$ASSET_FILE_NAME").use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        check(temp.renameTo(destination) || runCatching {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
            true
        }.getOrDefault(false)) {
            "Unable to install Arabic traineddata"
        }
        return root
    }

    override fun close() {
        tess?.recycle()
        tess = null
    }

    private fun retainArabicLines(text: String): String = text
        .lineSequence()
        .map(String::trim)
        .filter { line -> line.any(::isArabicLetter) }
        .distinct()
        .joinToString("\n")

    private fun isArabicLetter(char: Char): Boolean {
        if (!char.isLetter()) return false
        return Character.UnicodeBlock.of(char) in ARABIC_BLOCKS
    }

    companion object {
        private const val LANGUAGE = "ara"
        private const val ASSET_FILE_NAME = "ara.traineddata"

        private val ARABIC_BLOCKS = setOf(
            Character.UnicodeBlock.ARABIC,
            Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
            Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B,
            Character.UnicodeBlock.ARABIC_SUPPLEMENT,
            Character.UnicodeBlock.ARABIC_EXTENDED_A
        )
    }
}
