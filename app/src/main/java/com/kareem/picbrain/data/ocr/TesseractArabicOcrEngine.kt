package com.kareem.picbrain.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

class TesseractArabicOcrEngine(
    private val context: Context
) : OcrEngine {
    override val id: String = "tesseract-5.5.1-ara-best-v2"

    private val mutex = Mutex()
    private var tess: TessBaseAPI? = null

    override suspend fun recognize(uri: Uri): OcrResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val api = getOrCreateApi()
            val source = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: error("Unable to decode image for Arabic OCR")
            val working = scaleForOcr(source)

            try {
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                api.setImage(working)
                val raw = api.getUTF8Text().orEmpty()
                val arabicRelevant = retainArabicLines(raw)
                OcrResult(
                    rawText = arabicRelevant,
                    normalizedText = normalizeOcrText(arabicRelevant)
                )
            } finally {
                api.clear()
                if (working !== source) working.recycle()
                source.recycle()
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

    private fun scaleForOcr(source: Bitmap): Bitmap {
        if (source.width <= 0 || source.height <= 0) return source
        if (source.width >= TARGET_WIDTH) return source

        val widthScale = TARGET_WIDTH.toFloat() / source.width.toFloat()
        val pixelScale = kotlin.math.sqrt(MAX_PIXELS.toDouble() / (source.width.toLong() * source.height.toLong()).toDouble()).toFloat()
        val scale = min(MAX_SCALE, min(widthScale, pixelScale))
        if (scale <= 1.05f) return source

        val targetWidth = (source.width * scale).toInt().coerceAtLeast(source.width)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(source.height)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun prepareLanguageData(): File {
        val root = File(context.filesDir, MODEL_DIRECTORY)
        val tessDataDir = File(root, "tessdata")
        val destination = File(tessDataDir, ASSET_FILE_NAME)
        if (destination.isFile && destination.length() > MIN_MODEL_BYTES) return root

        check(tessDataDir.exists() || tessDataDir.mkdirs()) {
            "Unable to create Tesseract data directory"
        }

        val temp = File(tessDataDir, "$ASSET_FILE_NAME.tmp")
        context.assets.open("tessdata/$ASSET_FILE_NAME").use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        check(temp.length() > MIN_MODEL_BYTES) { "Arabic traineddata asset is invalid" }
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
        private const val MODEL_DIRECTORY = "tesseract-ara-best-v2"
        private const val TARGET_WIDTH = 1800
        private const val MAX_SCALE = 2.0f
        private const val MAX_PIXELS = 8_000_000L
        private const val MIN_MODEL_BYTES = 1_000_000L

        private val ARABIC_BLOCKS = setOf(
            Character.UnicodeBlock.ARABIC,
            Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
            Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B,
            Character.UnicodeBlock.ARABIC_SUPPLEMENT,
            Character.UnicodeBlock.ARABIC_EXTENDED_A
        )
    }
}
