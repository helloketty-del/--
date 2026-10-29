package com.futureyyy.tijing

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

enum class QuestionBankMatchStatus {
    EXACT,
    KEY_PHRASE,
    AMBIGUOUS,
    CONFLICT,
    NOT_FOUND,
}

data class LocalQuestionBankRecord(
    val id: String,
    val question: String,
    val type: QuestionType,
    val answerIndices: List<Int>,
    val answerText: String = "",
    val options: List<QuizOption> = emptyList(),
    val anchors: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
)

data class QuestionBankMatch(
    val status: QuestionBankMatchStatus,
    val record: LocalQuestionBankRecord? = null,
    val score: Double = 0.0,
    val candidates: List<LocalQuestionBankRecord> = emptyList(),
)

class QuestionBankIndex private constructor(
    private val records: List<LocalQuestionBankRecord>,
    private val exact: Map<String, List<LocalQuestionBankRecord>>,
    private val conflicts: Set<String>,
) {
    fun match(question: String): QuestionBankMatch {
        val query = normalize(question)
        if (query.isBlank()) return QuestionBankMatch(QuestionBankMatchStatus.NOT_FOUND)
        if (query in conflicts) return QuestionBankMatch(QuestionBankMatchStatus.CONFLICT)

        exact[query]?.let { candidates ->
            return if (candidates.size == 1) {
                QuestionBankMatch(QuestionBankMatchStatus.EXACT, candidates[0], 1.0, candidates)
            } else {
                QuestionBankMatch(QuestionBankMatchStatus.AMBIGUOUS, candidates = candidates)
            }
        }

        val ranked = records.mapNotNull { record ->
            val matched = record.anchors.filter(query::contains)
            val coverage = if (record.anchors.isEmpty()) 0.0 else matched.size.toDouble() / record.anchors.size
            val similarity = similarity(record.normalizedQuestion(), query)
            val ordered = orderedAnchors(query, matched)
            val score = coverage * 0.7 + similarity * 0.3
            if (ordered && coverage >= MIN_KEY_PHRASE_COVERAGE && score >= MIN_SIMILARITY) {
                RankedRecord(record, coverage, score)
            } else {
                null
            }
        }.sortedByDescending(RankedRecord::score)

        if (ranked.isEmpty()) return QuestionBankMatch(QuestionBankMatchStatus.NOT_FOUND)
        val best = ranked[0]
        val second = ranked.getOrNull(1)
        if (second != null && second.coverage >= MIN_KEY_PHRASE_COVERAGE && best.score - second.score < AMBIGUITY_SCORE_GAP) {
            return QuestionBankMatch(
                status = QuestionBankMatchStatus.AMBIGUOUS,
                candidates = ranked.take(MAX_CANDIDATES).map(RankedRecord::record),
            )
        }
        return QuestionBankMatch(
            status = QuestionBankMatchStatus.KEY_PHRASE,
            record = best.record,
            score = best.score,
            candidates = ranked.take(MAX_CANDIDATES).map(RankedRecord::record),
        )
    }

    fun toModelJson(match: QuestionBankMatch, recognizedQuestion: String, questionNo: Int): String {
        val record = match.record
        val json = JsonObject().apply {
            addProperty("question_no", questionNo)
            addProperty("question_type", record?.type?.jsonName() ?: "single")
            addProperty("question", record?.question ?: recognizedQuestion)
            add("options", JsonArray().apply {
                record?.options?.forEach { option ->
                    add(JsonObject().apply {
                        addProperty("index", option.index)
                        addProperty("text", option.text)
                    })
                }
            })
            add("answer_indices", JsonArray().apply {
                record?.answerIndices?.forEach(::add)
            })
            addProperty("answer_text", record?.answerText ?: "")
            add("confirm_box", JsonNull.INSTANCE)
            add("next_box", JsonNull.INSTANCE)
            addProperty("confidence", if (record == null) 0.0 else match.score.coerceIn(0.0, 1.0))
            addProperty("match_status", match.status.name.lowercase(Locale.ROOT))
            addProperty("match_label", match.status.label())
            addProperty("match_source", record?.sourceLabel() ?: "题库匹配")
            addProperty("answer_source", "question_bank")
        }
        return json.toString()
    }

    private data class RankedRecord(
        val record: LocalQuestionBankRecord,
        val coverage: Double,
        val score: Double,
    )

    companion object {
        private const val ANCHOR_LENGTH = 6
        private const val MAX_ANCHORS = 3
        private const val MIN_KEY_PHRASE_COVERAGE = 2.0 / 3.0
        private const val MIN_SIMILARITY = 0.46
        private const val AMBIGUITY_SCORE_GAP = 0.22
        private const val MAX_CANDIDATES = 5

        fun fromRecords(
            sourceRecords: List<LocalQuestionBankRecord>,
            conflictQuestions: Set<String> = emptySet(),
        ): QuestionBankIndex {
            val normalizedRecords = sourceRecords.map { record ->
                record.copy(anchors = record.anchors.map(::normalize).filter(String::isNotBlank))
            }
            val frequencies = mutableMapOf<String, Int>()
            normalizedRecords.forEach { record ->
                grams(normalize(record.question)).distinct().forEach { gram ->
                    frequencies[gram] = (frequencies[gram] ?: 0) + 1
                }
            }
            val enriched = normalizedRecords.map { record ->
                val anchors = if (record.anchors.isNotEmpty()) {
                    record.anchors
                } else {
                    selectAnchors(normalize(record.question), frequencies)
                }
                record.copy(anchors = anchors)
            }
            val exact = enriched.groupBy { normalize(it.question) }
            return QuestionBankIndex(
                records = enriched,
                exact = exact,
                conflicts = conflictQuestions.map(::normalize).toSet(),
            )
        }

        fun normalize(value: String): String {
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            return normalized.filter { char -> char.isLetterOrDigit() || char in '\u3400'..'\u9fff' }
        }

        private fun grams(value: String): List<String> {
            if (value.isBlank()) return emptyList()
            if (value.length <= ANCHOR_LENGTH) return listOf(value)
            return (0..value.length - ANCHOR_LENGTH).map { value.substring(it, it + ANCHOR_LENGTH) }.distinct()
        }

        private fun selectAnchors(value: String, frequencies: Map<String, Int>): List<String> {
            val candidates = grams(value)
            if (candidates.size <= MAX_ANCHORS) return candidates
            val last = max(0, value.length - ANCHOR_LENGTH)
            return listOf(0, (last * 0.5).toInt(), last).mapNotNull { target ->
                candidates.minByOrNull { gram ->
                    abs(value.indexOf(gram) - target) * 1_000_000 + (frequencies[gram] ?: 0)
                }
            }.distinct()
        }

        private fun orderedAnchors(query: String, anchors: List<String>): Boolean {
            var cursor = 0
            return anchors.all { anchor ->
                val position = query.indexOf(anchor, cursor)
                if (position < 0) false else {
                    cursor = position + 1
                    true
                }
            }
        }

        private fun similarity(left: String, right: String): Double {
            if (left.isBlank() || right.isBlank()) return 0.0
            val previous = IntArray(right.length + 1)
            for (i in left.indices) {
                var diagonal = 0
                for (j in right.indices) {
                    val saved = previous[j + 1]
                    previous[j + 1] = if (left[i] == right[j]) diagonal + 1 else max(previous[j + 1], previous[j])
                    diagonal = saved
                }
            }
            return previous[right.length].toDouble() / max(left.length, right.length)
        }

        private fun LocalQuestionBankRecord.normalizedQuestion(): String = normalize(question)

        private fun QuestionType.jsonName(): String = when (this) {
            QuestionType.SINGLE -> "single"
            QuestionType.MULTIPLE -> "multiple"
            QuestionType.INDEFINITE -> "indefinite"
            QuestionType.TRUE_FALSE -> "true_false"
            QuestionType.FILL_BLANK -> "fill_blank"
        }

        private fun QuestionBankMatchStatus.label(): String = when (this) {
            QuestionBankMatchStatus.EXACT -> "精确匹配"
            QuestionBankMatchStatus.KEY_PHRASE -> "关键短句匹配"
            QuestionBankMatchStatus.AMBIGUOUS -> "匹配不唯一"
            QuestionBankMatchStatus.CONFLICT -> "题库答案冲突"
            QuestionBankMatchStatus.NOT_FOUND -> "题库未命中"
        }

        private fun LocalQuestionBankRecord.sourceLabel(): String = sources.firstOrNull() ?: "题库记录"
    }
}

object QuestionStemParser {
    fun parse(raw: String): String {
        val text = raw.trim()
            .replaceFirst(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("题干识别响应不是有效 JSON", error)
        }
        val question = json.get("question")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim().orEmpty()
        require(question.isNotEmpty()) { "题干识别响应缺少 question" }
        return question
    }
}

class QuestionBankRepository(context: Context) {
    private val store = QuestionBankStore.get(context)

    val index: QuestionBankIndex by lazy { store.index }
}
