package com.kareem.picbrain.data.search

import androidx.sqlite.db.SimpleSQLiteQuery
import com.kareem.picbrain.data.db.MediaItemDao
import com.kareem.picbrain.data.db.MediaItemEntity
import com.kareem.picbrain.data.ocr.normalizeOcrText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.min

private const val DEFAULT_MIN_SEMANTIC_SCORE = 0.50f

data class SemanticHit(
    val mediaId: Long,
    val score: Float
)

data class SemanticDiagnostics(
    val bestScore: Float? = null,
    val acceptedCount: Int = 0,
    val evaluatedCount: Int = 0,
    val threshold: Float = DEFAULT_MIN_SEMANTIC_SCORE
)

interface SemanticSearchEngine {
    suspend fun search(query: String, limit: Int): List<SemanticHit>
}

class DisabledSemanticSearchEngine : SemanticSearchEngine {
    override suspend fun search(query: String, limit: Int): List<SemanticHit> = emptyList()
}

class HybridSearchRepository(
    private val dao: MediaItemDao,
    private val semantic: SemanticSearchEngine = DisabledSemanticSearchEngine()
) {
    private val _semanticDiagnostics = MutableStateFlow(SemanticDiagnostics())
    val semanticDiagnostics: StateFlow<SemanticDiagnostics> = _semanticDiagnostics.asStateFlow()

    private val _semanticScores = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val semanticScores: StateFlow<Map<Long, Float>> = _semanticScores.asStateFlow()

    suspend fun search(
        rawQuery: String,
        corpus: List<MediaItemEntity>,
        limit: Int = 200
    ): List<MediaItemEntity> {
        val normalizedQuery = normalizeOcrText(rawQuery)
        if (normalizedQuery.isBlank()) {
            clearSemanticDiagnostics()
            return emptyList()
        }

        val queryTokens = normalizedQuery.split(' ')
            .filter { it.length >= 2 || it.any(Char::isDigit) }
            .distinct()
        if (queryTokens.isEmpty()) {
            clearSemanticDiagnostics()
            return emptyList()
        }

        val byId = corpus.associateBy(MediaItemEntity::mediaId)
        val lexicalIds = lexicalCandidates(queryTokens, limit = 300)
        val fuzzyIds = fuzzyCandidates(queryTokens, corpus, limit = 300)
        val allSemanticHits = semantic.search(normalizedQuery, limit = 300)
        val semanticHits = allSemanticHits.filter { it.score >= MIN_SEMANTIC_SCORE }
        val semanticIds = semanticHits.map(SemanticHit::mediaId)
        val semanticScores = allSemanticHits.associate { it.mediaId to it.score }

        _semanticScores.value = semanticScores
        _semanticDiagnostics.value = SemanticDiagnostics(
            bestScore = allSemanticHits.maxOfOrNull(SemanticHit::score),
            acceptedCount = semanticHits.size,
            evaluatedCount = allSemanticHits.size,
            threshold = MIN_SEMANTIC_SCORE
        )

        val score = linkedMapOf<Long, Double>()
        addRrf(score, lexicalIds, weight = 1.35)
        addRrf(score, fuzzyIds, weight = 1.0)
        addRrf(score, semanticIds, weight = 1.15)

        return score.entries.asSequence()
            .mapNotNull { (mediaId, rrfScore) ->
                val item = byId[mediaId] ?: return@mapNotNull null
                val searchable = normalizeOcrText(item.ocrText.orEmpty())
                RankedItem(
                    item = item,
                    score = rrfScore,
                    semanticScore = semanticScores[mediaId],
                    exactPhrase = searchable.contains(normalizedQuery),
                    exactTokenCount = queryTokens.count(searchable::contains),
                    dateMillis = item.dateTakenMillis ?: item.dateAddedSeconds * 1000
                )
            }
            .sortedWith(
                compareByDescending<RankedItem> { it.exactPhrase }
                    .thenByDescending { it.exactTokenCount }
                    .thenByDescending { it.score }
                    .thenByDescending { it.semanticScore ?: Float.NEGATIVE_INFINITY }
                    .thenByDescending { it.dateMillis }
            )
            .take(limit)
            .map(RankedItem::item)
            .toList()
    }

    private fun clearSemanticDiagnostics() {
        _semanticScores.value = emptyMap()
        _semanticDiagnostics.value = SemanticDiagnostics()
    }

    private suspend fun lexicalCandidates(tokens: List<String>, limit: Int): List<Long> {
        val matchExpression = tokens.joinToString(" AND ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
        val sql = """
            SELECT m.mediaId
            FROM media_items m
            JOIN media_fts f ON m.mediaId = CAST(f.mediaId AS INTEGER)
            WHERE m.isScreenshot = 1
              AND m.ocrState = 'DONE'
              AND media_fts MATCH ?
            ORDER BY COALESCE(m.dateTakenMillis, m.dateAddedSeconds * 1000) DESC
            LIMIT ?
        """.trimIndent()
        return dao.searchMediaIds(SimpleSQLiteQuery(sql, arrayOf(matchExpression, limit)))
    }

    private fun fuzzyCandidates(
        queryTokens: List<String>,
        corpus: List<MediaItemEntity>,
        limit: Int
    ): List<Long> = corpus.asSequence()
        .mapNotNull { item ->
            val searchable = normalizeOcrText(item.ocrText.orEmpty())
            if (searchable.isBlank()) return@mapNotNull null
            val corpusTokens = searchable.split(' ').filter(String::isNotBlank).distinct()

            var exactCount = 0
            var totalDistance = 0
            for (queryToken in queryTokens) {
                if (searchable.contains(queryToken)) {
                    exactCount++
                    continue
                }

                val maxDistance = fuzzyTolerance(queryToken)
                if (maxDistance == 0) return@mapNotNull null
                val bestDistance = corpusTokens.asSequence()
                    .filter { abs(it.length - queryToken.length) <= maxDistance }
                    .map { boundedLevenshtein(queryToken, it, maxDistance) }
                    .filter { it <= maxDistance }
                    .minOrNull()
                    ?: return@mapNotNull null
                totalDistance += bestDistance
            }

            FuzzyHit(
                mediaId = item.mediaId,
                exactCount = exactCount,
                totalDistance = totalDistance,
                dateMillis = item.dateTakenMillis ?: item.dateAddedSeconds * 1000
            )
        }
        .sortedWith(
            compareByDescending<FuzzyHit> { it.exactCount }
                .thenBy { it.totalDistance }
                .thenByDescending { it.dateMillis }
        )
        .take(limit)
        .map(FuzzyHit::mediaId)
        .toList()

    private fun addRrf(target: MutableMap<Long, Double>, rankedIds: List<Long>, weight: Double) {
        rankedIds.forEachIndexed { index, mediaId ->
            val rank = index + 1
            target[mediaId] = target.getOrDefault(mediaId, 0.0) + weight / (RRF_K + rank)
        }
    }

    private fun fuzzyTolerance(token: String): Int = when {
        token.any(Char::isDigit) -> 0
        token.length < 4 -> 0
        token.length <= 6 -> 1
        else -> 2
    }

    private fun boundedLevenshtein(left: String, right: String, maxDistance: Int): Int {
        if (left == right) return 0
        if (abs(left.length - right.length) > maxDistance) return maxDistance + 1
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in 1..left.length) {
            current[0] = i
            var rowMinimum = current[0]
            for (j in 1..right.length) {
                val substitutionCost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + substitutionCost
                )
                rowMinimum = min(rowMinimum, current[j])
            }
            if (rowMinimum > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private data class FuzzyHit(
        val mediaId: Long,
        val exactCount: Int,
        val totalDistance: Int,
        val dateMillis: Long
    )

    private data class RankedItem(
        val item: MediaItemEntity,
        val score: Double,
        val semanticScore: Float?,
        val exactPhrase: Boolean,
        val exactTokenCount: Int,
        val dateMillis: Long
    )

    companion object {
        private const val RRF_K = 60.0
        const val MIN_SEMANTIC_SCORE = DEFAULT_MIN_SEMANTIC_SCORE
    }
}
