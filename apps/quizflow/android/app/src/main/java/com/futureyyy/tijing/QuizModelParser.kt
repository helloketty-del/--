package com.futureyyy.tijing

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal

enum class QuestionType {
    SINGLE,
    MULTIPLE,
    INDEFINITE,
    TRUE_FALSE,
    FILL_BLANK,
}

data class NormalizedBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()) {
            "坐标必须是有限数值"
        }
        require(x >= 0f && y >= 0f && width > 0f && height > 0f) {
            "坐标必须位于 0 到 100 之间"
        }
        require(x + width <= 100f && y + height <= 100f) {
            "坐标必须位于 0 到 100 之间"
        }
    }

    val centerX: Float
        get() = x + width / 2f

    val centerY: Float
        get() = y + height / 2f
}

data class QuizOption(
    val index: Int,
    val text: String,
    val box: NormalizedBox?,
)

data class QuizModelResult(
    val questionNo: Int?,
    val type: QuestionType,
    val question: String,
    val options: List<QuizOption>,
    val answerIndices: List<Int>,
    val confirmBox: NormalizedBox?,
    val nextBox: NormalizedBox?,
    val confidence: Double,
    val answerText: String? = null,
    val matchStatus: String? = null,
    val matchLabel: String? = null,
    val matchSource: String? = null,
    val answerSource: String? = null,
)

data class QuizActionPlan(
    val answerOptions: List<QuizOption>,
    val confirmBox: NormalizedBox?,
)

object QuizModelParser {
    private val openingFence = Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE)
    private val closingFence = Regex("\\s*```$")

    fun parse(raw: String): QuizModelResult {
        val text = raw.trim()
            .replaceFirst(openingFence, "")
            .replace(closingFence, "")
            .trim()
        val json = try {
            val parsed = JsonParser.parseString(text)
            require(parsed.isJsonObject) { "模型响应必须是 JSON 对象" }
            parsed.asJsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("模型响应不是有效 JSON", error)
        }

        val type = parseQuestionType(requiredText(json, "question_type"))
        val matchStatus = optionalText(json, "match_status")
        val allowEmpty = type == QuestionType.FILL_BLANK || matchStatus != null
        val options = parseOptions(json.get("options"), allowEmpty = allowEmpty)
        val optionIndices = options.map { it.index }.toSet()
        val answerIndices = parseAnswerIndices(
            json.get("answer_indices"),
            optionIndices,
            allowEmpty = type == QuestionType.FILL_BLANK || matchStatus != null,
        )
        if (!allowEmpty && (type == QuestionType.SINGLE || type == QuestionType.TRUE_FALSE)) {
            require(answerIndices.size == 1) { "${type.jsonName()} 题必须只有一个答案" }
        }

        val confidence = requiredDecimal(json, "confidence")
        require(confidence >= BigDecimal.ZERO && confidence <= BigDecimal.ONE) {
            "confidence 必须位于 0..1"
        }

        return QuizModelResult(
            questionNo = optionalInteger(json, "question_no"),
            type = type,
            question = requiredText(json, "question"),
            options = options,
            answerIndices = answerIndices,
            confirmBox = parseOptionalBox(json.get("confirm_box"), "confirm_box"),
            nextBox = parseOptionalBox(json.get("next_box"), "next_box"),
            confidence = confidence.toDouble(),
            answerText = optionalText(json, "answer_text"),
            matchStatus = matchStatus,
            matchLabel = optionalText(json, "match_label"),
            matchSource = optionalText(json, "match_source"),
            answerSource = optionalText(json, "answer_source")
                ?: if (matchStatus != null) "question_bank" else "vision",
        )
    }

    fun actionPlan(result: QuizModelResult, questionNo: Int): QuizActionPlan {
        require(questionNo > 0) { "questionNo 必须大于 0" }
        val optionByIndex = result.options.associateBy { it.index }
        val answerOptions = result.answerIndices.map { index ->
            val option = optionByIndex[index]
                ?: throw IllegalArgumentException("answer_indices 包含无效选项编号: $index")
            require(option.box != null) { "答案选项 $index 缺少有效 box" }
            option
        }
        return QuizActionPlan(
            answerOptions = answerOptions,
            confirmBox = result.confirmBox,
        )
    }

    private fun parseQuestionType(raw: String): QuestionType = when (raw) {
        "single" -> QuestionType.SINGLE
        "multiple" -> QuestionType.MULTIPLE
        "indefinite" -> QuestionType.INDEFINITE
        "true_false" -> QuestionType.TRUE_FALSE
        "fill_blank" -> QuestionType.FILL_BLANK
        else -> throw IllegalArgumentException("question_type 缺少或值不受支持")
    }

    private fun parseOptions(value: JsonElement?, allowEmpty: Boolean): List<QuizOption> {
        require(value != null && !value.isJsonNull && value.isJsonArray) { "options 缺少或为空" }
        val array = value.asJsonArray
        require(allowEmpty || array.size() > 0) { "options 缺少或为空" }
        val options = (0 until array.size()).map { position ->
            val element = array[position]
            require(element.isJsonObject) { "options[$position] 必须是对象" }
            val json = element.asJsonObject
            val index = requiredInteger(json, "index", "options[$position].index")
            require(index >= 0) { "options 包含无效选项编号" }
            QuizOption(
                index = index,
                text = requiredString(json, "text", "options[$position].text"),
                box = parseOptionalBox(json.get("box"), "options[$position].box"),
            )
        }
        require(options.map { it.index }.distinct().size == options.size) { "options 包含重复选项编号" }
        return options
    }

    private fun parseAnswerIndices(
        value: JsonElement?,
        optionIndices: Set<Int>,
        allowEmpty: Boolean,
    ): List<Int> {
        require(value != null && !value.isJsonNull && value.isJsonArray) { "answer_indices 缺少或为空" }
        val array = value.asJsonArray
        require(allowEmpty || array.size() > 0) { "answer_indices 缺少或为空" }
        val indices = (0 until array.size()).map { position ->
            val index = integerValue(array[position], "answer_indices[$position]")
            require(index in optionIndices) { "answer_indices 包含无效选项编号: $index" }
            index
        }
        require(indices.distinct().size == indices.size) { "answer_indices 包含重复选项编号" }
        return indices
    }

    private fun parseOptionalBox(value: JsonElement?, field: String): NormalizedBox? {
        if (value == null || value.isJsonNull) return null
        require(value.isJsonArray) { "$field 坐标格式无效" }
        val array = value.asJsonArray
        require(array.size() == 4) { "$field 坐标格式无效" }
        return try {
            val x = decimalValue(array[0], "$field[0]")
            val y = decimalValue(array[1], "$field[1]")
            val width = decimalValue(array[2], "$field[2]")
            val height = decimalValue(array[3], "$field[3]")
            require(x >= BigDecimal.ZERO && y >= BigDecimal.ZERO && width > BigDecimal.ZERO && height > BigDecimal.ZERO) {
                "坐标必须位于 0 到 100 之间"
            }
            require(x.add(width) <= HUNDRED && y.add(height) <= HUNDRED) {
                "坐标必须位于 0 到 100 之间"
            }
            NormalizedBox(
                x = x.toFloat(),
                y = y.toFloat(),
                width = width.toFloat(),
                height = height.toFloat(),
            )
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$field ${error.message}", error)
        }
    }

    private fun optionalInteger(json: JsonObject, field: String): Int? {
        val value = json.get(field)
        if (value == null || value.isJsonNull) return null
        return integerValue(value, field)
    }

    private fun requiredInteger(json: JsonObject, field: String, label: String): Int {
        val value = json.get(field)
        require(value != null && !value.isJsonNull) { "$label 缺少" }
        return integerValue(value, label)
    }

    private fun integerValue(value: JsonElement, field: String): Int {
        return try {
            decimalValue(value, field).intValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("$field 必须是 32 位整数", error)
        }
    }

    private fun requiredDecimal(json: JsonObject, field: String): BigDecimal {
        val value = json.get(field)
        require(value != null && !value.isJsonNull) { "$field 缺少" }
        return decimalValue(value, field)
    }

    private fun decimalValue(value: JsonElement, field: String): BigDecimal {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$field 必须是数值" }
        return try {
            value.asJsonPrimitive.asBigDecimal
        } catch (error: Exception) {
            throw IllegalArgumentException("$field 必须是有限数值", error)
        }
    }

    private fun requiredText(json: JsonObject, field: String): String {
        val value = requiredString(json, field, field).trim()
        require(value.isNotEmpty()) { "$field 缺少或为空" }
        return value
    }

    private fun requiredString(json: JsonObject, field: String, label: String): String {
        val value = json.get(field)
        require(value != null && !value.isJsonNull) { "$label 缺少" }
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$label 必须是字符串" }
        return value.asString
    }

    private fun optionalText(json: JsonObject, field: String): String? {
        val value = json.get(field) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$field 必须是字符串" }
        return value.asString.trim().ifEmpty { null }
    }

    private fun QuestionType.jsonName(): String = name.lowercase()

    private val HUNDRED = BigDecimal("100")
}
