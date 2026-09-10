package com.kareem.picbrain.data.search

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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

        if (!inputData.getBoolean(KEY_SKIP_DIAGNOSTIC, false)) {
            val diagnostic = runCatching { SemanticModelDiagnostic(applicationContext).run() }
                .getOrElse { error ->
                    return Result.failure(workDataOf(KEY_LAST_ERROR to "Diagnostic bootstrap failed: ${describe(error)}".take(MAX_ERROR_CHARS)))
                }
            if (!diagnostic.documentPathHealthy) {
                return Result.failure(workDataOf(KEY_LAST_ERROR to "Semantic diagnostic: ${diagnostic.compact()}".take(MAX_ERROR_CHARS)))
            }
        }

        val app = applicationContext as PicBrainApp
        val dao = app.database.mediaItemDao()
        val embedder = EmbeddingGemmaEmbedder(applicationContext)

        return try {
            val screenshots = dao.getScreenshotsNeedingEmbedding(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS,
                BATCH_SIZE,
                MAX_ATTEMPTS
            )
            if (screenshots.isEmpty()) {
                return Result.success(workDataOf(KEY_COMPLETE to true, KEY_INDEXED to 0, KEY_FAILED to 0))
            }

            var indexed = 0
            var failed = 0
            var skipped = 0
            var lastError = ""
            var current = 0

            for (item in screenshots) {
                coroutineContext.ensureActive()
                current++
                val now = System.currentTimeMillis()
                dao.markSemanticRunning(item.mediaId, now)

                val source = sanitizeForEmbedding(item.ocrNormalizedText.orEmpty())
                if (source.isBlank()) {
                    skipped++
                    dao.markSemanticFailed(
                        item.mediaId,
                        "Blank OCR text after semantic sanitization",
                        now,
                        MAX_ATTEMPTS
                    )
                    continue
                }

                val result: kotlin.Result<EmbeddedDocument> = embedWithFreshGraphRetries(embedder, source)

                result.onSuccess { embedded ->
                    dao.deleteEmbeddingsForMedia(
                        item.mediaId,
                        EmbeddingGemmaEmbedder.MODEL_ID,
                        EmbeddingGemmaEmbedder.TARGET_DIMENSIONS
                    )
                    dao.upsertEmbeddings(
                        listOf(createEmbeddingEntity(item.mediaId, embedded.text, embedded.vector))
                    )
                    dao.markSemanticDone(item.mediaId, System.currentTimeMillis())
                    indexed++
                }.onFailure { error ->
                    failed++
                    lastError = describe(error).take(MAX_ERROR_CHARS)
                    dao.markSemanticFailed(
                        item.mediaId,
                        lastError,
                        System.currentTimeMillis(),
                        MAX_ATTEMPTS
                    )
                }

                setProgress(
                    workDataOf(
                        KEY_CURRENT_ITEM to current,
                        KEY_INDEXED to indexed,
                        KEY_SKIPPED to skipped,
                        KEY_FAILED to failed,
                        KEY_BATCH_TOTAL to screenshots.size,
                        KEY_LAST_ERROR to lastError
                    )
                )
            }

            val hasMore = dao.getScreenshotsNeedingEmbedding(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS,
                1,
                MAX_ATTEMPTS
            ).isNotEmpty()

            if (hasMore) {
                enqueueNextBatch()
                Result.success(
                    workDataOf(
                        KEY_CURRENT_ITEM to current,
                        KEY_BATCH_TOTAL to screenshots.size,
                        KEY_INDEXED to indexed,
                        KEY_SKIPPED to skipped,
                        KEY_FAILED to failed,
                        KEY_CONTINUE to true,
                        KEY_LAST_ERROR to lastError
                    )
                )
            } else {
                Result.success(
                    workDataOf(
                        KEY_CURRENT_ITEM to current,
                        KEY_BATCH_TOTAL to screenshots.size,
                        KEY_COMPLETE to true,
                        KEY_INDEXED to indexed,
                        KEY_SKIPPED to skipped,
                        KEY_FAILED to failed,
                        KEY_LAST_ERROR to lastError
                    )
                )
            }
        } catch (error: Throwable) {
            Result.failure(workDataOf(KEY_LAST_ERROR to describe(error).take(MAX_ERROR_CHARS)))
        } finally {
            embedder.close()
        }
    }

    private suspend fun embedWithFreshGraphRetries(
        embedder: EmbeddingGemmaEmbedder,
        original: String
    ): kotlin.Result<EmbeddedDocument> {
        var limit = minOf(original.length, SAFE_INITIAL_CHARS)
        var lastError: Throwable? = null

        while (limit >= MIN_DOCUMENT_CHARS) {
            val candidate = original.take(limit).trim()
            if (candidate.isBlank()) break

            try {
                return kotlin.Result.success(
                    EmbeddedDocument(candidate, embedder.embedDocument(candidate))
                )
            } catch (error: Throwable) {
                lastError = error
                if (!isSequenceTooLong(error)) {
                    return kotlin.Result.failure(error)
                }
                limit = nextSmallerLimit(limit)
            }
        }

        return kotlin.Result.failure(
            lastError ?: IllegalStateException("No embeddable text after safe semantic shrinking")
        )
    }

    private fun nextSmallerLimit(current: Int): Int {
        if (current <= MIN_DOCUMENT_CHARS) return 0
        return when {
            current > 160 -> 160
            current > 120 -> 120
            current > 80 -> 80
            current > 48 -> 48
            current > 32 -> 32
            current > MIN_DOCUMENT_CHARS -> MIN_DOCUMENT_CHARS
            else -> 0
        }
    }

    private fun isSequenceTooLong(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return "max_seq_len" in message ||
            "input text is too long" in message ||
            "token_ids.size" in message
    }

    private fun enqueueNextBatch() {
        val next = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setInputData(workDataOf(KEY_SKIP_DIAGNOSTIC to true))
            .build()

        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                next
            )
            .enqueue()
    }

    private fun sanitizeForEmbedding(raw: String): String {
        if (raw.isBlank()) return ""
        val cleaned = buildString(minOf(raw.length, MAX_SANITIZED_CHARS)) {
            raw.forEach { ch ->
                if (length >= MAX_SANITIZED_CHARS) return@forEach
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

    private fun describe(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val message = root.message?.replace(WHITESPACE, " ")?.trim().orEmpty()
        return "${root::class.java.simpleName}: $message"
    }

    private data class EmbeddedDocument(val text: String, val vector: FloatArray)

    companion object {
        const val UNIQUE_WORK_NAME = "picbrain-semantic-embeddings"
        const val KEY_CURRENT_ITEM = "current_item"
        const val KEY_INDEXED = "indexed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_BATCH_TOTAL = "batch_total"
        const val KEY_COMPLETE = "complete"
        const val KEY_CONTINUE = "continue"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_SKIP_DIAGNOSTIC = "skip_diagnostic"

        private const val BATCH_SIZE = 16
        private const val MAX_ATTEMPTS = 3
        private const val SAFE_INITIAL_CHARS = 220
        private const val MAX_SANITIZED_CHARS = 700
        private const val MIN_DOCUMENT_CHARS = 24
        private const val MAX_ERROR_CHARS = 1200
        private val WHITESPACE = Regex("\\s+")
    }
}
