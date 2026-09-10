package com.kareem.picbrain.data.search

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kareem.picbrain.PicBrainApp
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class EmbeddingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = SemanticModelStore(applicationContext)
        if (!store.isInstalled()) return Result.success(workDataOf(KEY_INDEXED to 0))

        val app = applicationContext as PicBrainApp
        val dao = app.database.mediaItemDao()
        val embedder = EmbeddingGemmaEmbedder(applicationContext)

        return try {
            val screenshots = dao.getScreenshotsNeedingEmbedding(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS,
                BATCH_SIZE
            )

            if (screenshots.isEmpty()) {
                return Result.success(workDataOf(KEY_COMPLETE to true, KEY_INDEXED to 0))
            }

            var indexed = 0
            var failed = 0
            var lastError = ""

            for (item in screenshots) {
                coroutineContext.ensureActive()
                val source = sanitizeForEmbedding(item.ocrNormalizedText.orEmpty())
                if (source.isBlank()) continue

                runCatching {
                    val vector = embedder.embedDocument(source)
                    dao.deleteEmbeddingsForMedia(
                        item.mediaId,
                        EmbeddingGemmaEmbedder.MODEL_ID,
                        EmbeddingGemmaEmbedder.TARGET_DIMENSIONS
                    )
                    dao.upsertEmbeddings(listOf(createEmbeddingEntity(item.mediaId, source, vector)))
                    indexed++
                }.onFailure {
                    failed++
                    lastError = "${it::class.java.simpleName}: ${it.message.orEmpty()}".take(MAX_ERROR_CHARS)
                }

                setProgress(
                    workDataOf(
                        KEY_INDEXED to indexed,
                        KEY_FAILED to failed,
                        KEY_BATCH_TOTAL to screenshots.size,
                        KEY_LAST_ERROR to lastError
                    )
                )
            }

            val hasMore = dao.getScreenshotsNeedingEmbedding(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS,
                1
            ).isNotEmpty()

            when {
                indexed == 0 && failed > 0 -> Result.failure(
                    workDataOf(KEY_INDEXED to indexed, KEY_FAILED to failed, KEY_LAST_ERROR to lastError)
                )
                hasMore -> Result.success(
                    workDataOf(KEY_INDEXED to indexed, KEY_FAILED to failed, KEY_CONTINUE to true, KEY_LAST_ERROR to lastError)
                )
                else -> Result.success(
                    workDataOf(KEY_COMPLETE to true, KEY_INDEXED to indexed, KEY_FAILED to failed, KEY_LAST_ERROR to lastError)
                )
            }
        } catch (error: Throwable) {
            Result.failure(
                workDataOf(KEY_LAST_ERROR to "${error::class.java.simpleName}: ${error.message.orEmpty()}".take(MAX_ERROR_CHARS))
            )
        } finally {
            embedder.close()
        }
    }

    private fun sanitizeForEmbedding(raw: String): String {
        if (raw.isBlank()) return ""
        val cleaned = buildString(minOf(raw.length, MAX_DOCUMENT_CHARS)) {
            raw.forEach { ch ->
                if (length >= MAX_DOCUMENT_CHARS) return@forEach
                when {
                    ch == '\n' || ch == '\r' || ch == '\t' -> append(' ')
                    ch.code in 0x20..0x7E -> append(ch)
                    ch.code >= 0x00A0 && !Character.isSurrogate(ch) && ch != '\uFFFD' -> append(ch)
                    else -> append(' ')
                }
            }
        }
        return cleaned.replace(WHITESPACE, " ").trim()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "picbrain-semantic-embeddings"
        const val KEY_INDEXED = "indexed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_BATCH_TOTAL = "batch_total"
        const val KEY_COMPLETE = "complete"
        const val KEY_CONTINUE = "continue"
        const val KEY_LAST_ERROR = "last_error"

        private const val BATCH_SIZE = 8
        // Conservative bound: OCR text can tokenize far more densely than character count suggests.
        private const val MAX_DOCUMENT_CHARS = 700
        private const val MAX_ERROR_CHARS = 1200
        private val WHITESPACE = Regex("\\s+")
    }
}
