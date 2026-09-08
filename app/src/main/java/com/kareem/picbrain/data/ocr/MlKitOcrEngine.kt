package com.kareem.picbrain.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class MlKitOcrEngine(private val context: Context) : OcrEngine {
    override val id: String = "mlkit-latin-16.0.1"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(uri: Uri): OcrResult {
        val image = InputImage.fromFilePath(context, uri)
        val result = recognizer.process(image).await()
        return OcrResult(result.text, normalizeOcrText(result.text))
    }

    override fun close() {
        recognizer.close()
    }
}
