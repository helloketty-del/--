package com.futureyyy.tijing

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Writable question bank backed by app-private files. The packaged asset is only a seed:
 * copied once, never overwritten afterwards, so edits and auto-captured records survive
 * APK upgrades (they are lost only on uninstall or clearing app data).
 */
class QuestionBankStore private constructor(private val appContext: Context) {
    data class PageResult(
        val records: List<LocalQuestionBankRecord>,
        val total: Int,
        val offset: Int,
    )

    data class ImportResult(
        val ok: Boolean,
        val count: Int,
        val error: String?,
    )

    data class SaveResult(
        val ok: Boolean,
        val id: String?,
        val error: String?,
    )

    private val bankFile = File(appContext.filesDir, FILE_NAME)
    private val lock = Any()
    @Volatile
    private var cachedIndex: QuestionBankIndex? = null

    val index: QuestionBankIndex
        get() = cachedIndex ?: synchronized(lock) { cachedIndex ?: buildIndexLocked().also { cachedIndex = it } }

    fun snapshot(): List<LocalQuestionBankRecord> = synchronized(lock) { readRecordsLocked() }

    fun page(query: String, offset: Int, limit: Int): PageResult = synchronized(lock) {
        val needle = query.trim().lowercase(Locale.ROOT)
        val all = readRecordsLocked()
        val filtered = if (needle.isBlank()) all else {
            all.filter { record -> record.question.lowercase(Locale.ROOT).contains(needle) }
        }
        val from = offset.coerceIn(0, filtered.size)
        val to = (from + limit.coerceAtLeast(1)).coerceAtMost(filtered.size)
        PageResult(filtered.subList(from, to).toList(), filtered.size, from)
    }

    fun findById(id: String): LocalQuestionBankRecord? = synchronized(lock) {
        readRecordsLocked().firstOrNull { record -> record.id == id }
    }

    fun upsert(record: LocalQuestionBankRecord): Boolean = synchronized(lock) {
        val records = readRecordsLocked().toMutableList()
        val existing = records.indexOfFirst { current -> current.id == record.id }
        if (existing >= 0) records[existing] = record else records.add(record)
        writeRecordsLocked(records)
        true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val records = readRecordsLocked().toMutableList()
        val removed = records.removeAll { record -> record.id == id }
        if (removed) writeRecordsLocked(records)
        removed
    }

    /** Persists a vision-fallback answer, keyed by the normalized question text. */
    fun writeBackFallback(
        question: String,
        type: QuestionType,
        options: List<QuizOption>,
        answerIndices: List<Int>,
        answerText: String,
    ): Boolean = synchronized(lock) {
        val text = question.trim()
        if (text.isEmpty()) return@synchronized false
        val normalized = QuestionBankIndex.normalize(text)
        if (normalized.isBlank()) return@synchronized false
        val records = readRecordsLocked().toMutableList()
        val id = fallbackId(normalized)
        val existing = records.indexOfFirst { record ->
            record.id == id || QuestionBankIndex.normalize(record.question) == normalized
        }
        val record = LocalQuestionBankRecord(
            id = id,
            question = text,
            type = type,
            answerIndices = answerIndices.sorted().distinct(),
            answerText = answerText,
            options = options.map { option -> QuizOption(option.index, option.text, null) },
            anchors = emptyList(),
            sources = listOf(FALLBACK_SOURCE),
        )
        if (existing >= 0) records[existing] = record else records.add(record)
        writeRecordsLocked(records)
        Log.i(TAG, "bank-writeback id=$id q=$text")
        true
    }

    fun exportJson(pretty: Boolean): String = synchronized(lock) {
        val root = buildRootLocked(readRecordsLocked(), readConflictsLocked())
        if (pretty) PRETTY_GSON.toJson(root) else root.toString()
    }

    /** JSON payload for the web layer: { total, offset, records: [...] }. */
    fun pageJson(query: String, offset: Int, limit: Int): String = synchronized(lock) {
        val result = page(query, offset, limit)
        JsonObject().apply {
            addProperty("total", result.total)
            addProperty("offset", result.offset)
            add("records", JsonArray().apply {
                result.records.forEach { record -> add(recordToJson(record)) }
            })
        }.toString()
    }

    fun saveRecordJson(raw: String): SaveResult = synchronized(lock) {
        val element = try {
            JsonParser.parseString(raw)
        } catch (error: Exception) {
            return@synchronized SaveResult(false, null, "题目 JSON 解析失败")
        }
        if (!element.isJsonObject) return@synchronized SaveResult(false, null, "题目数据无效")
        val record = parseRecords(JsonArray().apply { add(element) }).firstOrNull()
            ?: return@synchronized SaveResult(false, null, "题目数据无效")
        if (record.question.isBlank()) return@synchronized SaveResult(false, null, "题干不能为空")
        val id = record.id.trim().ifBlank { fallbackId(QuestionBankIndex.normalize(record.question)) }
        val saved = record.copy(id = id)
        val records = readRecordsLocked().toMutableList()
        val existing = records.indexOfFirst { current -> current.id == id }
        if (existing >= 0) records[existing] = saved else records.add(saved)
        writeRecordsLocked(records)
        SaveResult(true, id, null)
    }

    fun importJson(raw: String): ImportResult = synchronized(lock) {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (error: Exception) {
            return@synchronized ImportResult(false, 0, "题库 JSON 解析失败")
        }
        val records = parseRecords(recordsArray(root))
        if (records.isEmpty()) return@synchronized ImportResult(false, 0, "题库内容为空")
        writeRecordsLocked(records)
        ImportResult(true, records.size, null)
    }

    private fun buildIndexLocked(): QuestionBankIndex {
        val root = readRootLocked()
        val records = parseRecords(recordsArray(root))
        val conflicts = parseConflicts(root.getAsJsonArray("conflicts"))
        return QuestionBankIndex.fromRecords(records, conflicts)
    }

    /**
     * The bundled bank keeps the raw import list under `records` and the de-duplicated,
     * matchable set under `questions`; the matcher has always used `questions`.
     */
    private fun recordsArray(root: JsonObject): JsonArray? =
        root.getAsJsonArray("questions")?.takeIf { array -> array.size() > 0 }
            ?: root.getAsJsonArray("records")

    private fun readRecordsLocked(): List<LocalQuestionBankRecord> =
        parseRecords(recordsArray(readRootLocked()))

    private fun readRootLocked(): JsonObject = try {
        JsonParser.parseString(bankFile.readText(Charsets.UTF_8)).asJsonObject
    } catch (error: Exception) {
        Log.w(TAG, "bank-read-failed, reseeding: ${error.message}")
        seedFromAsset()
        JsonParser.parseString(bankFile.readText(Charsets.UTF_8)).asJsonObject
    }

    private fun ensureSeeded() {
        if (!bankFile.exists()) seedFromAsset()
    }

    private fun seedFromAsset() {
        try {
            appContext.assets.open(SEED_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                bankFile.writeText(reader.readText(), Charsets.UTF_8)
            }
        } catch (error: Exception) {
            Log.e(TAG, "bank-seed-failed: ${error.message}")
            if (!bankFile.exists()) bankFile.writeText("""{"schema_version":1,"questions":[]}""", Charsets.UTF_8)
        }
    }

    private fun writeRecordsLocked(records: List<LocalQuestionBankRecord>) {
        cachedIndex = null
        writeFileLocked(buildRootLocked(records, readConflictsLocked()))
    }

    private fun readConflictsLocked(): Set<String> = try {
        parseConflicts(readRootLocked().getAsJsonArray("conflicts"))
    } catch (error: Exception) {
        emptySet()
    }

    private fun writeFileLocked(root: JsonObject) {
        ensureSeeded()
        try {
            val temp = File(appContext.filesDir, "$FILE_NAME.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (bankFile.exists() && !bankFile.delete()) {
                temp.copyTo(bankFile, overwrite = true)
                temp.delete()
            } else if (!temp.renameTo(bankFile)) {
                temp.copyTo(bankFile, overwrite = true)
                temp.delete()
            }
        } catch (error: Exception) {
            Log.e(TAG, "bank-write-failed: ${error.message}")
        }
    }

    private fun buildRootLocked(
        records: List<LocalQuestionBankRecord>,
        conflicts: Set<String>,
    ): JsonObject = JsonObject().apply {
        addProperty("schema_version", SCHEMA_VERSION)
        add("questions", JsonArray().apply {
            records.forEach { record -> add(recordToJson(record)) }
        })
        add("conflicts", JsonArray().apply {
            conflicts.forEach { question ->
                add(JsonObject().apply { addProperty("normalized_question", question) })
            }
        })
    }

    private fun recordToJson(record: LocalQuestionBankRecord): JsonObject = JsonObject().apply {
        addProperty("id", record.id)
        addProperty("question", record.question)
        addProperty("type", record.type.jsonName())
        addProperty("answer", answerLetters(record.answerIndices))
        add("answer_indices", JsonArray().apply { record.answerIndices.forEach(::add) })
        addProperty("answer_text", record.answerText)
        add("options", JsonArray().apply {
            record.options.forEach { option ->
                add(JsonObject().apply {
                    addProperty("index", option.index)
                    addProperty("text", option.text)
                })
            }
        })
        add("anchors", JsonArray().apply { record.anchors.forEach(::add) })
        add("sources", JsonArray().apply {
            record.sources.forEach { source -> add(JsonObject().apply { addProperty("file", source) }) }
        })
    }

    private fun parseRecords(array: JsonArray?): List<LocalQuestionBankRecord> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val json = element.asJsonObject
            val id = json.get("id")?.takeIf(JsonElement::isJsonPrimitive)?.asString ?: return@mapNotNull null
            val question = json.get("question")?.takeIf(JsonElement::isJsonPrimitive)?.asString ?: return@mapNotNull null
            LocalQuestionBankRecord(
                id = id,
                question = question,
                type = parseType(json.get("type")?.takeIf(JsonElement::isJsonPrimitive)?.asString),
                answerIndices = json.getAsJsonArray("answer_indices")?.mapNotNull { item ->
                    item.takeIf(JsonElement::isJsonPrimitive)?.asInt
                }.orEmpty(),
                answerText = json.get("answer_text")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty(),
                options = json.getAsJsonArray("options")?.mapNotNull { item ->
                    if (!item.isJsonObject) return@mapNotNull null
                    val option = item.asJsonObject
                    val index = option.get("index")?.takeIf(JsonElement::isJsonPrimitive)?.asInt ?: return@mapNotNull null
                    val text = option.get("text")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
                    QuizOption(index, text, null)
                }.orEmpty(),
                anchors = json.getAsJsonArray("anchors")?.mapNotNull { item ->
                    item.takeIf(JsonElement::isJsonPrimitive)?.asString
                }.orEmpty(),
                sources = parseSources(json.getAsJsonArray("sources")),
            )
        }
    }

    private fun parseSources(array: JsonArray?): List<String> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            when {
                element.isJsonPrimitive -> element.asString
                element.isJsonObject -> {
                    val json = element.asJsonObject
                    listOfNotNull(
                        json.get("file")?.takeIf(JsonElement::isJsonPrimitive)?.asString,
                        json.get("sheet")?.takeIf(JsonElement::isJsonPrimitive)?.asString,
                        json.get("row")?.takeIf(JsonElement::isJsonPrimitive)?.asInt?.toString(),
                    ).joinToString(" / ").ifBlank { null }
                }
                else -> null
            }
        }
    }

    private fun parseConflicts(array: JsonArray?): Set<String> {
        if (array == null) return emptySet()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            element.asJsonObject.get("normalized_question")?.takeIf(JsonElement::isJsonPrimitive)?.asString
        }.toSet()
    }

    private fun fallbackId(normalizedQuestion: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizedQuestion.toByteArray(Charsets.UTF_8))
        val hex = digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
        return "$FALLBACK_ID_PREFIX$hex"
    }

    private fun answerLetters(indices: List<Int>): String =
        indices.sorted().joinToString(",") { index ->
            if (index in 0..25) ('A'.code + index).toChar().toString() else "#${index + 1}"
        }

    private fun QuestionType.jsonName(): String = when (this) {
        QuestionType.SINGLE -> "single"
        QuestionType.MULTIPLE -> "multiple"
        QuestionType.INDEFINITE -> "indefinite"
        QuestionType.TRUE_FALSE -> "true_false"
        QuestionType.FILL_BLANK -> "fill_blank"
    }

    private fun parseType(raw: String?): QuestionType = when (raw) {
        "multiple" -> QuestionType.MULTIPLE
        "indefinite" -> QuestionType.INDEFINITE
        "true_false" -> QuestionType.TRUE_FALSE
        "fill_blank" -> QuestionType.FILL_BLANK
        else -> QuestionType.SINGLE
    }

    init {
        ensureSeeded()
    }

    companion object {
        private const val TAG = "TiJingBank"
        private const val FILE_NAME = "question-bank.json"
        private const val SEED_ASSET = "question-bank.json"
        private const val SCHEMA_VERSION = 1
        const val FALLBACK_ID_PREFIX = "auto-"
        const val FALLBACK_SOURCE = "auto_fallback"
        private val PRETTY_GSON = GsonBuilder().setPrettyPrinting().create()

        @Volatile
        private var instance: QuestionBankStore? = null

        fun get(context: Context): QuestionBankStore =
            instance ?: synchronized(this) {
                instance ?: QuestionBankStore(context.applicationContext).also { instance = it }
            }
    }
}
