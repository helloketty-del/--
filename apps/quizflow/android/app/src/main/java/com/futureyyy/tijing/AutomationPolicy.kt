package com.futureyyy.tijing

import com.google.gson.JsonParser

enum class FlowPage { QUESTION, ANSWER_SHEET, SUBMIT_DIALOG, RESULT, LOADING, POPUP, UNKNOWN }
enum class FlowSwipe { LEFT, RIGHT, UP, DOWN }

data class FlowOption(val text: String, val box: NormalizedBox?, val selected: Boolean?)
data class FlowObservation(
    val page: FlowPage,
    val confidence: Double,
    val key: String = "",
    val question: String = "",
    val complete: Boolean = false,
    val options: List<FlowOption> = emptyList(),
    val answers: List<String> = emptyList(),
    val scroll: FlowSwipe? = null,
    val confirm: NormalizedBox? = null,
    val next: NormalizedBox? = null,
    val submit: NormalizedBox? = null,
    val submitConfirm: NormalizedBox? = null,
    val last: Boolean = false,
    val unanswered: Int? = null,
    val submissionSucceeded: Boolean = false,
    val number: Int? = null,
    val total: Int? = null,
    val popup: PopupObservation? = null,
) {
    companion object {
        fun parse(raw: String, imageWidth: Int? = null, imageHeight: Int? = null, ignoreNavigationBoxes: Boolean = false): FlowObservation {
            val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JsonParser.parseString(text).asJsonObject
            fun str(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
            fun bool(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
            fun integer(name: String): Int? {
                val value = json.get(name)?.takeUnless { it.isJsonNull } ?: return null
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name 必须是整数" }
                return try { value.asBigDecimal.intValueExact() }
                catch (e: ArithmeticException) { throw IllegalArgumentException("$name 必须是整数", e) }
            }
            val coordinates = ModelCoordinates.fromResponse(json, imageWidth, imageHeight)
            fun box(name: String) = coordinates.box(json.get(name), name)
            val options = json.getAsJsonArray("options")?.mapIndexed { index, item ->
                val o = item.asJsonObject
                FlowOption(o.get("text").asString,
                    coordinates.box(o.get("box"), "options[$index].box"),
                    o.get("selected")?.takeUnless { it.isJsonNull }?.asBoolean)
            }.orEmpty()
            val confidence = json.get("confidence").asDouble
            require(confidence.isFinite() && confidence in 0.0..1.0) { "识别置信度无效" }
            val unanswered = integer("unanswered_count")
            require(unanswered == null || unanswered >= 0) { "未答题数无效" }
            val number = integer("visible_question_number")
            val total = integer("total_questions")
            require(number == null || number in 1..9999) { "可见题号无效" }
            require(total == null || total in 1..9999) { "总题数无效" }
            require(number == null || total == null || number <= total) { "可见题号超过总数" }
            return FlowObservation(
                FlowPage.valueOf(str("page_kind").uppercase()), confidence,
                str("question_key"), str("question"), bool("question_complete"), options,
                json.getAsJsonArray("answer_texts")?.map { it.asString }.orEmpty(),
                str("scroll_direction").takeIf { it.isNotEmpty() && it != "none" }?.let { FlowSwipe.valueOf(it.uppercase()) },
                if (ignoreNavigationBoxes) null else box("confirm_box"), if (ignoreNavigationBoxes) null else box("next_box"), box("submit_box"), box("submit_confirm_box"),
                bool("is_last_question"), unanswered, bool("submission_succeeded"), number, total,
                if (str("page_kind").equals("popup", true)) PopupObservation(str("popup_kind"), str("popup_title"),
                    str("popup_message"), str("popup_button_text"), box("popup_button_box")) else null,
            )
        }
    }
}

sealed class FlowAction {
    data class Tap(val box: NormalizedBox, val purpose: String) : FlowAction()
    data class Swipe(val direction: FlowSwipe, val navigation: Boolean = false) : FlowAction()
    data class Confirm(val box: NormalizedBox?, val attempt: Int) : FlowAction()
    data class CustomTap(val point: TapPoint, val purpose: String) : FlowAction()
    data class Pause(val reason: String) : FlowAction()
    data object Wait : FlowAction()
    data object Done : FlowAction()
}

/** Decisions never use coordinates from previous screenshots. Each tap is re-observed. */
class AutomationPolicy(
    private val navigation: String = "confirm",
    private val autoSubmit: Boolean = false,
    private val minConfidence: Double = 0.85,
    private val maxScrolls: Int = 12,
    private val customClicks: CustomClicks = CustomClicks(),
) {
    private var questionKey = ""
    private var awaitingNext = false
    private var confirmationAttempts = 0
    private var customConfirmSent = false
    private var questionConfirmed = false
    private var popupAttempts = 0
    private var singleTapMayAdvance = false
    private var stableAnswers: Set<String>? = null
    private var scrolls = 0
    private var sameActions = 0
    private var lastAction = ""
    private var submitted = false
    private var submitConfirmed = false
    private var completedQuestions = 0
    private var visibleNumber: Int? = null
    private var expectedTotal: Int? = null
    private val completedNumbers = mutableSetOf<Int>()
    private val verified = mutableSetOf<String>()
    private val normalized: (String) -> String = { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC).filter(Char::isLetterOrDigit).lowercase() }

    fun resetRetryBudget() { sameActions = 0; lastAction = ""; scrolls = 0; popupAttempts = 0 }

    fun context(): String = "当前已跟踪题目标识=$questionKey；已核实选中选项=${verified.joinToString("、")}；等待翻题=$awaitingNext。只对相同题目标识使用这些信息。"

    fun decide(o: FlowObservation): FlowAction {
        if (o.confidence < minConfidence) return FlowAction.Pause("识别不确定，请人工核对后重试")
        if (o.page == FlowPage.POPUP) {
            if (++popupAttempts > 3) return FlowAction.Pause("弹窗连续出现，已暂停以避免重复操作")
            val action = PopupHandling.decide(o.popup, confirmationAttempts > 0 || awaitingNext || singleTapMayAdvance)
            if (action is FlowAction.Tap && PopupHandling.confirmsAnswer(o.popup)) questionConfirmed = true
            if (o.popup?.kind == "answer_feedback" && !PopupHandling.confirmsAnswer(o.popup)) customConfirmSent = false
            return action
        }
        popupAttempts = 0
        if (o.total != null) {
            if (expectedTotal != null && expectedTotal != o.total) return FlowAction.Pause("总题数识别前后不一致")
            expectedTotal = o.total
        }
        if (o.page == FlowPage.RESULT) {
            return if ((submitted || submitConfirmed) && o.submissionSucceeded) FlowAction.Done
            else FlowAction.Pause("已到结果页；尚未核实本次提交成功")
        }
        if (o.page == FlowPage.SUBMIT_DIALOG) {
            if (!autoSubmit) return FlowAction.Pause("已到提交确认窗口，自动提交未开启")
            if (!submitted || completedQuestions == 0) return FlowAction.Pause("未经过本次交卷流程，请人工核对")
            if (!allAnswered(o)) return FlowAction.Pause("交卷窗口未确认全部作答，暂停提交")
            if (submitConfirmed) return FlowAction.Pause("已点击最终确认，尚未出现提交成功页")
            val box = o.submitConfirm ?: return FlowAction.Pause("无法定位最终提交确认按钮")
            submitConfirmed = true
            return guarded("submit-confirm", FlowAction.Tap(box, "确认提交"))
        }
        if (o.page == FlowPage.ANSWER_SHEET) {
            if (!awaitingNext || completedQuestions == 0) return FlowAction.Pause("到达答题卡，请核对题目是否全部完成")
            return submit(o)
        }
        if (o.page == FlowPage.LOADING) return guarded("loading", FlowAction.Wait)
        if (o.page != FlowPage.QUESTION) return FlowAction.Pause("无法确认当前页面类型")
        if (o.key.isBlank()) return FlowAction.Pause("缺少稳定题目标识，无法确认是否翻题")
        if (questionKey.isEmpty()) questionKey = o.key
        if (o.key != questionKey) {
            if (!awaitingNext && !singleTapMayAdvance) return FlowAction.Pause("题目意外切换，已暂停以避免漏题")
            if (!awaitingNext) {
                completedQuestions += 1
                visibleNumber?.let(completedNumbers::add)
            }
            questionKey = o.key
            awaitingNext = false
            confirmationAttempts = 0
            customConfirmSent = false
            questionConfirmed = false
            singleTapMayAdvance = false
            stableAnswers = null
            visibleNumber = null
            verified.clear()
            scrolls = 0
            sameActions = 0
            lastAction = ""
        } else if (awaitingNext) {
            // Never toggle answers while waiting for navigation to finish.
            if (o.last && !needsConfirmation(o)) return submit(o)
            return navigate(o)
        }
        if (o.number != null) visibleNumber = o.number
        if (!o.complete) {
            return scroll(o.scroll ?: FlowSwipe.UP)
        }
        val targets = o.answers.map(normalized).toSet()
        if (targets.isEmpty() || targets.any(String::isBlank)) return FlowAction.Pause("没有得到可靠答案")
        if (stableAnswers != null && stableAnswers != targets) return FlowAction.Pause("同题答案发生变化，请人工核对")
        stableAnswers = targets
        val options = o.options.associateBy { normalized(it.text) }
        if (options.size != o.options.size) return FlowAction.Pause("选项文字重复，无法唯一定位")
        if (!options.keys.containsAll(targets)) return FlowAction.Pause("答案未匹配到当前题目选项")
        o.options.forEach { option ->
            val text = normalized(option.text)
            if (option.selected == true) verified.add(text)
            if (option.selected == false && option.box != null) verified.remove(text)
        }
        // A visibly selected wrong option must be removed before continuing.
        o.options.firstOrNull { it.selected == true && normalized(it.text) !in targets }?.let {
            return guarded("unselect:${normalized(it.text)}", FlowAction.Tap(it.box
                ?: return FlowAction.Pause("错误选项不在当前屏幕"), "取消错误选项"))
        }
        val missing = targets - verified
        if (missing.isNotEmpty()) {
            val candidate = missing.mapNotNull(options::get).firstOrNull { it.box != null }
            if (candidate != null) {
                if (candidate.selected == null) return FlowAction.Pause("无法辨认选项是否选中，避免重复点击")
                singleTapMayAdvance = targets.size == 1
                return guarded("answer:${normalized(candidate.text)}", FlowAction.Tap(candidate.box!!, "选择答案"))
            }
            return scroll(o.scroll ?: FlowSwipe.DOWN)
        }
        completedQuestions += 1
        visibleNumber?.let(completedNumbers::add)
        awaitingNext = true
        if (o.last && !needsConfirmation(o)) return submit(o)
        return navigate(o)
    }

    private fun needsConfirmation(o: FlowObservation): Boolean = !questionConfirmed &&
        ((navigation == "confirm" && o.confirm != null) || (navigation == "tap" && customClicks.confirmFirst && !customConfirmSent))

    private fun navigate(o: FlowObservation): FlowAction {
        if (navigation == "tap") {
            if (needsConfirmation(o)) {
                if (++confirmationAttempts > 3) return FlowAction.Pause("自定义提交仍未生效，请检查点击位置")
                customConfirmSent = true
                return FlowAction.CustomTap(customClicks.confirm, "自定义提交本题")
            }
            return guarded("custom-next", FlowAction.CustomTap(customClicks.next, "自定义下一题"))
        }
        if (navigation == "confirm") {
            if (o.confirm != null && !questionConfirmed) {
                confirmationAttempts += 1
                return guarded("confirm", FlowAction.Confirm(o.confirm, confirmationAttempts))
            }
            o.next?.let { return guarded("next", FlowAction.Tap(it, "下一题")) }
            return guarded("wait-next", FlowAction.Wait)
        }
        // Page navigation is always a real screen swipe, regardless of visible buttons.
        val direction = when (navigation) {
            "left" -> FlowSwipe.LEFT
            "right" -> FlowSwipe.RIGHT
            else -> FlowSwipe.UP
        }
        return guarded("navigate:$direction", FlowAction.Swipe(direction, true))
    }

    private fun submit(o: FlowObservation): FlowAction {
        if (!autoSubmit) return FlowAction.Pause("已完成当前题，自动提交未开启")
        if (!allAnswered(o)) return FlowAction.Pause("未确认全部作答，暂停交卷")
        if (submitted) return guarded("wait-submit", FlowAction.Wait)
        val box = o.submit ?: return FlowAction.Pause("无法定位交卷按钮")
        submitted = true
        return guarded("submit", FlowAction.Tap(box, "提交全部答案"))
    }

    private fun allAnswered(o: FlowObservation): Boolean {
        if (o.unanswered != null) return o.unanswered == 0
        val total = expectedTotal ?: return false
        return (1..total).all(completedNumbers::contains)
    }

    private fun scroll(direction: FlowSwipe): FlowAction {
        if (++scrolls > maxScrolls) return FlowAction.Pause("本题滚动次数达到上限，请检查长题页面")
        return guarded("scroll:$direction", FlowAction.Swipe(direction))
    }

    private fun guarded(key: String, action: FlowAction): FlowAction {
        sameActions = if (lastAction == key) sameActions + 1 else 1
        lastAction = key
        return if (sameActions > 3) FlowAction.Pause("页面没有响应或动作未生效，请核对后重试") else action
    }
}
