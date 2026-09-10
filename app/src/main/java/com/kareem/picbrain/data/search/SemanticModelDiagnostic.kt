package com.kareem.picbrain.data.search

import android.content.Context
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SemanticDiagnosticResult(
    val name: String,
    val passed: Boolean,
    val dimensions: Int?,
    val error: String?
)

data class SemanticDiagnosticReport(
    val results: List<SemanticDiagnosticResult>
) {
    val documentPathHealthy: Boolean
        get() = results.firstOrNull { it.name == "document_en_title" }?.passed == true &&
            results.firstOrNull { it.name == "document_ar_title" }?.passed == true

    fun compact(): String = results.joinToString(" | ") { result ->
        if (result.passed) {
            "${result.name}=PASS(${result.dimensions ?: 0})"
        } else {
            "${result.name}=FAIL(${result.error.orEmpty()})"
        }
    }
}

class SemanticModelDiagnostic(context: Context) {
    private val appContext = context.applicationContext
    private val modelStore = SemanticModelStore(appContext)

    suspend fun run(): SemanticDiagnosticReport = withContext(Dispatchers.Default) {
        check(modelStore.isInstalled()) { "Semantic model is not installed" }

        val embedder = TextEmbedder.createFromFile(appContext, modelStore.modelFile.absolutePath)
        try {
            val results = mutableListOf<SemanticDiagnosticResult>()

            results += runCase(embedder, "query_en", "hello world", queryContext())
            results += runCase(embedder, "query_ar", "الرنين المغناطيسي", queryContext())
            results += runCase(embedder, "document_en_title", "hello world", documentContext(withTitle = true))
            results += runCase(embedder, "document_en_no_title", "hello world", documentContext(withTitle = false))
            results += runCase(embedder, "document_ar_title", "الرنين المغناطيسي", documentContext(withTitle = true))
            results += runCase(embedder, "document_ar_no_title", "الرنين المغناطيسي", documentContext(withTitle = false))
            results += runPlainCase(embedder, "plain_en", "hello world")

            repeat(3) { index ->
                results += runCase(
                    embedder,
                    "document_repeat_${index + 1}",
                    "PicBrain repeated document embedding test ${index + 1}",
                    documentContext(withTitle = true)
                )
            }

            SemanticDiagnosticReport(results)
        } finally {
            embedder.close()
        }
    }

    private fun runCase(
        embedder: TextEmbedder,
        name: String,
        text: String,
        formatContext: TextEmbedder.TextFormatContext
    ): SemanticDiagnosticResult = runCatching {
        val dimensions = embedder.embed(text, formatContext)
            .embeddingResult().embeddings().firstOrNull()?.floatEmbedding()?.size
            ?: error("no float embedding")
        SemanticDiagnosticResult(name, true, dimensions, null)
    }.getOrElse { error ->
        SemanticDiagnosticResult(name, false, null, describe(error))
    }

    private fun runPlainCase(
        embedder: TextEmbedder,
        name: String,
        text: String
    ): SemanticDiagnosticResult = runCatching {
        val dimensions = embedder.embed(text)
            .embeddingResult().embeddings().firstOrNull()?.floatEmbedding()?.size
            ?: error("no float embedding")
        SemanticDiagnosticResult(name, true, dimensions, null)
    }.getOrElse { error ->
        SemanticDiagnosticResult(name, false, null, describe(error))
    }

    private fun queryContext(): TextEmbedder.TextFormatContext =
        TextEmbedder.TextFormatContext.builder()
            .setTaskType(TextEmbedder.EmbeddingType.RETRIEVAL_QUERY)
            .setRole(TextEmbedder.TextRole.QUERY)
            .build()

    private fun documentContext(withTitle: Boolean): TextEmbedder.TextFormatContext {
        val builder = TextEmbedder.TextFormatContext.builder()
            .setTaskType(TextEmbedder.EmbeddingType.RETRIEVAL_DOCUMENT)
            .setRole(TextEmbedder.TextRole.DOCUMENT)
        if (withTitle) builder.setTitle("PicBrain screenshot")
        return builder.build()
    }

    private fun describe(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val message = root.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return "${root::class.java.simpleName}:${message}".take(MAX_ERROR_CHARS)
    }

    companion object {
        private const val MAX_ERROR_CHARS = 420
    }
}
