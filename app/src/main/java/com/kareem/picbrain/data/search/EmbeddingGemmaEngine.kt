package com.kareem.picbrain.data.search

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.kareem.picbrain.data.db.EmbeddingEntity
import com.kareem.picbrain.data.db.MediaItemDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

class SemanticModelStore(private val context: Context) {
    val modelFile: File
        get() = File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() > MIN_MODEL_BYTES

    suspend fun importFrom(uri: Uri): Long = withContext(Dispatchers.IO) {
        val parent = modelFile.parentFile ?: error("Model directory unavailable")
        check(parent.exists() || parent.mkdirs()) { "Unable to create model directory" }

        val temp = File(parent, "$MODEL_FILE_NAME.tmp")
        if (temp.exists()) temp.delete()

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to read selected model")

            check(temp.length() > MIN_MODEL_BYTES) {
                "Selected file is too small to be an EmbeddingGemma task model"
            }

            validateModelFile(temp)

            if (modelFile.exists() && !modelFile.delete()) {
                error("Unable to replace existing semantic model")
            }
            check(temp.renameTo(modelFile) || runCatching {
                temp.copyTo(modelFile, overwrite = true)
                temp.delete()
                true
            }.getOrDefault(false)) { "Unable to install semantic model" }

            modelFile.length()
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun validateModelFile(file: File) {
        val embedder = runCatching { TextEmbedder.createFromFile(context, file.absolutePath) }
            .getOrElse { cause ->
                throw IllegalArgumentException(
                    "This is not a MediaPipe-compatible EmbeddingGemma text-embedding model",
                    cause
                )
            }

        try {
            val formatContext = TextEmbedder.TextFormatContext.builder()
                .setTaskType(TextEmbedder.EmbeddingType.RETRIEVAL_QUERY)
                .setRole(TextEmbedder.TextRole.QUERY)
                .build()
            val dimensions = embedder.embed("PicBrain model validation", formatContext)
                .embeddingResult()
                .embeddings()
                .firstOrNull()
                ?.floatEmbedding()
                ?.size
                ?: 0
            check(dimensions >= EmbeddingGemmaEmbedder.TARGET_DIMENSIONS) {
                "Model output has $dimensions dimensions; PicBrain requires at least ${EmbeddingGemmaEmbedder.TARGET_DIMENSIONS}"
            }
        } catch (error: Throwable) {
            throw IllegalArgumentException(
                "The selected model could not produce EmbeddingGemma retrieval embeddings",
                error
            )
        } finally {
            embedder.close()
        }
    }

    companion object {
        const val MODEL_FILE_NAME = "embedding_gemma.task"
        private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L
    }
}

class EmbeddingGemmaEmbedder(
    private val context: Context,
    private val dimensions: Int = TARGET_DIMENSIONS
) : AutoCloseable {
    private val mutex = Mutex()
    private val modelStore = SemanticModelStore(context)
    private var embedder: TextEmbedder? = null

    fun isAvailable(): Boolean = modelStore.isInstalled()

    suspend fun embedQuery(text: String): FloatArray = embed(text, queryContext())

    suspend fun embedDocument(text: String): FloatArray = embed(text, documentContext())

    private suspend fun embed(
        text: String,
        formatContext: TextEmbedder.TextFormatContext
    ): FloatArray = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(text.isNotBlank()) { "Cannot embed blank text" }
            val task = getOrCreateEmbedder()
            val full = task.embed(text, formatContext)
                .embeddingResult()
                .embeddings()
                .firstOrNull()
                ?.floatEmbedding()
                ?: error("EmbeddingGemma returned no float embedding")

            require(full.size >= dimensions) {
                "Embedding dimension ${full.size} is smaller than requested $dimensions"
            }
            normalize(full.copyOf(dimensions))
        }
    }

    private fun getOrCreateEmbedder(): TextEmbedder {
        embedder?.let { return it }
        check(modelStore.isInstalled()) { "Semantic model is not installed" }
        return TextEmbedder.createFromFile(context, modelStore.modelFile.absolutePath).also {
            embedder = it
        }
    }

    private fun queryContext(): TextEmbedder.TextFormatContext =
        TextEmbedder.TextFormatContext.builder()
            .setTaskType(TextEmbedder.EmbeddingType.RETRIEVAL_QUERY)
            .setRole(TextEmbedder.TextRole.QUERY)
            .build()

    private fun documentContext(): TextEmbedder.TextFormatContext =
        TextEmbedder.TextFormatContext.builder()
            .setTaskType(TextEmbedder.EmbeddingType.RETRIEVAL_DOCUMENT)
            .setRole(TextEmbedder.TextRole.DOCUMENT)
            .setTitle("PicBrain screenshot")
            .build()

    override fun close() {
        embedder?.close()
        embedder = null
    }

    companion object {
        const val MODEL_ID = "embeddinggemma-300m-mediapipe-retrieval-v1"
        const val TARGET_DIMENSIONS = 256

        private fun normalize(vector: FloatArray): FloatArray {
            var sum = 0.0
            for (value in vector) sum += value.toDouble() * value.toDouble()
            val norm = sqrt(sum).toFloat()
            if (norm <= 0f) return vector
            for (i in vector.indices) vector[i] /= norm
            return vector
        }
    }
}

class EmbeddingGemmaSemanticEngine(
    context: Context,
    private val dao: MediaItemDao
) : SemanticSearchEngine, AutoCloseable {
    private val embedder = EmbeddingGemmaEmbedder(context.applicationContext)

    override suspend fun search(query: String, limit: Int): List<Long> {
        if (!embedder.isAvailable() || query.isBlank()) return emptyList()

        return runCatching {
            val queryVector = embedder.embedQuery(query)
            dao.getEmbeddings(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS
            ).asSequence()
                .mapNotNull { entity ->
                    val vector = decodeVector(entity.vector, entity.dimensions)
                    if (vector.size != queryVector.size) return@mapNotNull null
                    entity.mediaId to dot(queryVector, vector)
                }
                .groupBy({ it.first }, { it.second })
                .map { (mediaId, scores) -> mediaId to (scores.maxOrNull() ?: -1f) }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        }.getOrDefault(emptyList())
    }

    override fun close() = embedder.close()
}

fun encodeVector(vector: FloatArray): ByteArray =
    ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { vector.forEach(::putFloat) }
        .array()

fun decodeVector(bytes: ByteArray, dimensions: Int): FloatArray {
    if (bytes.size != dimensions * Float.SIZE_BYTES) return FloatArray(0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(dimensions) { buffer.float }
}

fun textSha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

fun createEmbeddingEntity(
    mediaId: Long,
    sourceText: String,
    vector: FloatArray,
    createdAtMillis: Long = System.currentTimeMillis()
): EmbeddingEntity = EmbeddingEntity(
    mediaId = mediaId,
    chunkIndex = 0,
    modelId = EmbeddingGemmaEmbedder.MODEL_ID,
    dimensions = EmbeddingGemmaEmbedder.TARGET_DIMENSIONS,
    textHash = textSha256(sourceText),
    sourceText = sourceText,
    vector = encodeVector(vector),
    createdAtMillis = createdAtMillis
)

private fun dot(left: FloatArray, right: FloatArray): Float {
    var result = 0f
    for (i in left.indices) result += left[i] * right[i]
    return result
}
