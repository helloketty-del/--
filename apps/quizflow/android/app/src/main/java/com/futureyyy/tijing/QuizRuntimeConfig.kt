package com.futureyyy.tijing

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.net.URI

enum class QuizMode {
    AUTO,
    MANUAL,
    CAPTURE,
}

enum class QuizAnswerSource {
    VISION,
    QUESTION_BANK,
}

enum class QuizTargetApp(
    val id: String,
    val packageName: String,
    val displayName: String,
) {
    AUTO("auto", "", "当前题目页"),
    WECHAT("wechat", "com.tencent.mm", "微信题库"),
    SIMULATOR("simulator", "com.futureyyy.tijingsimulator", "题库模拟器"),
    ;

    val isAutomatic: Boolean
        get() = packageName.isBlank()

    companion object {
        fun fromId(value: String): QuizTargetApp = entries.firstOrNull { it.id == value }
            ?: throw IllegalArgumentException("targetApp 必须是 auto、wechat 或 simulator")
    }
}

data class QuizRuntimeConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val prompt: String,
    val questionBankPrompt: String,
    val answerSource: QuizAnswerSource,
    val mode: QuizMode,
    val targetApp: QuizTargetApp,
    val timeoutMs: Int,
    val retryCount: Int,
    val startingQuestionNo: Int = 1,
    val navigation: String = "confirm",
    val customClicks: CustomClicks = CustomClicks(),
    val gestureTestOnly: Boolean = false,
    val swipeProfiles: Map<FlowSwipe, SwipePath> = SwipePath.defaults(),
    val autoSubmit: Boolean = false,
    val bankFallback: Boolean = true,
    val minConfidence: Double = 0.85,
    val maxScrolls: Int = 12,
    val settleMs: Int = 1000,
    val automationInstructions: String = "",
) {
    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val DEFAULT_RETRY_COUNT = 2
        private val DEFAULT_PROMPT = """
            你是一个严谨的识题与答题引擎。请读取当前截图，识别完整题干、题型、全部选项和“确认”按钮，并选择最符合题意的答案。

            当前题号：{QUESTION_NO}
            题目截图：{QUESTION_IMAGE}

            规则：
            1. question_type 只能是 single、multiple、indefinite 或 true_false。
            2. answer_indices 使用从 0 开始的选项编号；多选题和不定项选择题返回全部正确答案，且编号不得重复。
            3. 仔细识别“不正确”“错误的是”“不包括”“不属于”“最主要”“首要”等限定词；涉及地区、时间、条件或活动时以题干条件为准。
            4. 每个 option 必须返回 box: [x,y,width,height]，表示该选项实际可点击区域。
            5. 所有题型选择答案后都需要点击“确认”才能进入下一页；confirm_box 返回当前题“确认”按钮的可点击区域。
            6. confirm_box 只表示本题确认按钮，不得把最终“提交”按钮作为 confirm_box；看不清确认按钮时返回 null。
            7. 所有坐标均为截图宽高的 0 到 100 百分比，并确保 x+width、y+height 不超过 100。
            8. confidence 使用 0 到 1 的小数。
            9. question_no 必须等于 {QUESTION_NO}。
            10. 只返回一个合法 JSON 对象，不要 Markdown 代码围栏、分析过程或额外说明。

            返回结构：
            {"question_no":{QUESTION_NO},"question_type":"single","question":"完整题干","options":[{"index":0,"text":"完整选项文字","box":[8,43,84,8]}],"answer_indices":[0],"confirm_box":[20,88,60,8],"next_box":null,"confidence":0.95,"next_action":"tap_answer_then_confirm"}
        """.trimIndent()
        private val DEFAULT_QUESTION_BANK_PROMPT = """
            你是题库题干提取器。请只读取当前截图中的题干，不要解题，不要输出选项答案，不要输出坐标。

            题目截图：{QUESTION_IMAGE}

            规则：
            1. 完整抄录题干，保留“不正确”“错误的是”“不包括”“不属于”等限定词。
            2. 去除题号、状态栏、按钮文字和选项内容。
            3. 填空题保留题干中的括号、下划线或空缺位置。
            4. 只返回一个合法 JSON 对象，不要 Markdown 或额外说明。

            返回结构：{"question":"完整题干"}
        """.trimIndent()

        fun fromJson(raw: String): QuizRuntimeConfig {
            val json = try {
                val parsed = JsonParser.parseString(raw)
                require(parsed.isJsonObject) { "运行配置必须是 JSON 对象" }
                parsed.asJsonObject
            } catch (error: Exception) {
                throw IllegalArgumentException("运行配置不是有效 JSON", error)
            }

            val endpoint = requiredText(json, "endpoint")
            requireValidEndpoint(endpoint)
            val model = requiredText(json, "model")
            val gestureTestOnly = json.get("gestureTestOnly")?.asBoolean ?: false
            val apiKey = if (gestureTestOnly) optionalText(json, "apiKey", "") else requiredText(json, "apiKey")
            val prompt = optionalText(json, "prompt", DEFAULT_PROMPT).trim().ifBlank { DEFAULT_PROMPT }
            val questionBankPrompt = optionalText(json, "questionBankPrompt", DEFAULT_QUESTION_BANK_PROMPT)
                .trim()
                .ifBlank { DEFAULT_QUESTION_BANK_PROMPT }
            val answerSource = when (optionalText(json, "answerSource", "vision").trim().lowercase()) {
                "question_bank" -> QuizAnswerSource.QUESTION_BANK
                "vision" -> QuizAnswerSource.VISION
                else -> throw IllegalArgumentException("answerSource 必须是 vision 或 question_bank")
            }
            val mode = when (optionalText(json, "mode", "manual").trim().lowercase()) {
                "auto" -> QuizMode.AUTO
                "manual" -> QuizMode.MANUAL
                "capture" -> QuizMode.CAPTURE
                else -> throw IllegalArgumentException("mode 必须是 auto、manual 或 capture")
            }
            val targetApp = QuizTargetApp.fromId(
                optionalText(json, "targetApp", QuizTargetApp.AUTO.id).trim().lowercase(),
            )
            val timeoutMs = optionalInteger(json, "timeoutMs", DEFAULT_TIMEOUT_MS)
            val retryCount = optionalInteger(json, "retryCount", DEFAULT_RETRY_COUNT)
            val startingQuestionNo = optionalInteger(json, "startQuestionNo", 1)

            require(timeoutMs in 5_000..120_000) { "timeoutMs 必须位于 5000..120000" }
            require(retryCount in 0..5) { "retryCount 必须位于 0..5" }
            require(startingQuestionNo in 1..9_999) { "startQuestionNo 必须位于 1..9999" }

            return QuizRuntimeConfig(
                endpoint = endpoint,
                model = model,
                apiKey = apiKey,
                prompt = prompt,
                questionBankPrompt = questionBankPrompt,
                answerSource = answerSource,
                mode = mode,
                targetApp = targetApp,
                timeoutMs = timeoutMs,
                retryCount = retryCount,
                startingQuestionNo = startingQuestionNo,
                navigation = optionalText(json, "navigation", "confirm").let {
                    when (it) {
                        "auto" -> "confirm"
                        "down" -> "up"
                        "confirm", "tap", "up", "left", "right" -> it
                        else -> throw IllegalArgumentException("翻题方向必须是上滑、左滑或右滑")
                    }
                },
                swipeProfiles = SwipePath.parseProfiles(json.get("swipeProfiles")),
                customClicks = CustomClicks.parse(json.get("customClicks")),
                gestureTestOnly = gestureTestOnly,
                autoSubmit = json.get("autoSubmit")?.asBoolean ?: false,
                bankFallback = json.get("bankFallback")?.asBoolean ?: true,
                minConfidence = (json.get("minConfidence")?.asDouble ?: 0.85).also {
                    require(it.isFinite() && it in 0.5..1.0) { "置信度应在 0.5..1.0" }
                },
                maxScrolls = optionalInteger(json, "maxScrolls", 12).also { require(it in 1..40) },
                settleMs = optionalInteger(json, "settleMs", 1000).also { require(it in 500..10000) },
                automationInstructions = optionalText(json, "automationInstructions", ""),
            )
        }

        private fun requiredText(json: JsonObject, field: String): String {
            val raw = json.get(field)
            require(raw != null && !raw.isJsonNull) { "$field 缺少或为空" }
            require(raw.isJsonPrimitive && raw.asJsonPrimitive.isString) { "$field 必须是字符串" }
            val value = raw.asString.trim()
            require(value.isNotEmpty()) { "$field 缺少或为空" }
            return value
        }

        private fun optionalText(json: JsonObject, field: String, default: String): String {
            val value = json.get(field) ?: return default
            require(!value.isJsonNull && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "$field 必须是字符串"
            }
            return value.asString
        }

        private fun optionalInteger(json: JsonObject, field: String, default: Int): Int {
            val value = json.get(field) ?: return default
            require(!value.isJsonNull && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                "$field 必须是整数"
            }
            return try {
                value.asBigDecimal().intValueExact()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("$field 必须是 32 位整数", error)
            } catch (error: NumberFormatException) {
                throw IllegalArgumentException("$field 必须是整数", error)
            }
        }

        private fun requireValidEndpoint(endpoint: String) {
            val uri = try {
                URI(endpoint)
            } catch (error: Exception) {
                throw IllegalArgumentException("endpoint 不是有效 URL", error)
            }
            require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
                "endpoint 必须使用 http 或 https"
            }
            require(!uri.host.isNullOrBlank()) { "endpoint 缺少主机名" }
            require(uri.port == -1 || uri.port in 1..65_535) { "endpoint 端口必须位于 1..65535" }
        }

        private fun JsonElement.asBigDecimal(): BigDecimal = try {
            asJsonPrimitive.asBigDecimal
        } catch (error: Exception) {
            throw NumberFormatException(error.message)
        }
    }
}
