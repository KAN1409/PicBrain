package com.kareem.picbrain.data.ocr

import android.net.Uri
import com.kareem.picbrain.data.db.MediaItemDao

class ScreenshotOcrProcessor(
    private val dao: MediaItemDao,
    private val engine: OcrEngine
) {
    suspend fun processPending(limit: Int = 25): OcrBatchResult {
        val pending = dao.getScreenshotsNeedingOcr(limit)
        var success = 0
        var failed = 0
        for (item in pending) {
            val now = System.currentTimeMillis()
            runCatching { engine.recognize(Uri.parse(item.contentUri)) }
                .onSuccess {
                    dao.markOcrDone(item.mediaId, it.rawText, it.normalizedText, engine.id, now)
                    success++
                }
                .onFailure {
                    dao.markOcrFailed(item.mediaId, engine.id, now, (it.message ?: it::class.java.simpleName).take(500))
                    failed++
                }
        }
        return OcrBatchResult(pending.size, success, failed)
    }
}

data class OcrBatchResult(val attempted: Int, val success: Int, val failed: Int)
