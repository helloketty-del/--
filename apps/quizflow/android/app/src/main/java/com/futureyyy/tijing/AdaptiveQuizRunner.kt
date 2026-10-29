package com.futureyyy.tijing

interface FlowGestures {
    fun isForeground(): Boolean
    fun tap(box: NormalizedBox, callback: (Boolean) -> Unit)
    fun confirm(box: NormalizedBox?, attempt: Int, callback: (Boolean) -> Unit) {
        if (box == null) callback(false) else tap(box, callback)
    }
    fun swipe(direction: FlowSwipe, navigation: Boolean, callback: (Boolean) -> Unit)
    fun cancel()
}

class AdaptiveQuizRunner(
    private val config: QuizRuntimeConfig,
    private val capture: CaptureGateway,
    private val model: FlowModel,
    private val gestures: FlowGestures,
    private val listener: RunnerListener,
    private val scheduler: RunnerScheduler,
) : QuizRunner {
    private var phase = RunnerPhase.READY
    private var generation = 0L
    private var task: RunnerScheduledTask? = null
    private var error: String? = null
    private var no = config.startingQuestionNo
    private var key = ""
    private var completionMessage: String? = null
    private val frames = mutableListOf<CaptureFrame>()
    private var policy = newPolicy()

    private fun newPolicy() = AutomationPolicy(config.navigation, config.autoSubmit, config.minConfidence, config.maxScrolls)

    @Synchronized override fun onPrimaryAction() { if (phase == RunnerPhase.READY) observe() }
    @Synchronized override fun pause() { fail("已暂停") }
    @Synchronized override fun resume() { if (phase == RunnerPhase.PAUSED_ERROR) { error = null; policy.resetRetryBudget(); observe() } }
    @Synchronized override fun retry() = resume()
    @Synchronized override fun stop() { invalidate(); phase = RunnerPhase.STOPPED; publish() }
    @Synchronized override fun snapshot() = RunnerSnapshot(phase, no, QuizMode.AUTO, errorMessage = error,
        message = error ?: completionMessage ?: when (phase) {
            RunnerPhase.CAPTURING -> "正在截图"
            RunnerPhase.RECOGNIZING -> "正在识别题目与选中状态"
            RunnerPhase.EXECUTING -> "正在点击或翻题"
            RunnerPhase.STOPPED -> "运行已结束"
            else -> "等待页面稳定"
        })

    private fun invalidate() { generation += 1; task?.cancel(); task = null; model.cancel(); capture.cancel(); gestures.cancel() }
    private fun publish() { listener.onSnapshot(snapshot()) }
    private fun fail(message: String) { invalidate(); error = message; phase = RunnerPhase.PAUSED_ERROR; publish(); listener.onError(message) }

    @Synchronized private fun observe() {
        if (phase == RunnerPhase.STOPPED) return
        if (!gestures.isForeground()) { fail("请保持原题目应用在前台"); return }
        val token = ++generation
        phase = RunnerPhase.CAPTURING; publish()
        capture.capture { result -> synchronized(this) {
            if (token != generation) return@synchronized
            val frame = result.getOrElse { fail(it.message ?: "截图失败"); return@synchronized }
            // Preserve first pages for long stems, plus the latest screen for coordinates.
            if (frames.isEmpty() || frames.last().signature != frame.signature) frames.add(frame)
            if (frames.size > 8) frames.removeAt(frames.lastIndex - 1)
            val context = if (frames.last() === frame) frames.toList() else frames + frame
            phase = RunnerPhase.RECOGNIZING; publish()
            model.observe(context, no, policy.context()) { response -> synchronized(this) responseLock@ {
                if (token != generation) return@responseLock
                val o = response.getOrElse { fail(it.message ?: "视觉识别失败"); return@responseLock }
                if (o.page == FlowPage.QUESTION && o.key.isNotEmpty() && o.key != key) {
                    if (key.isNotEmpty()) no += 1
                    key = o.key
                    frames.clear(); frames.add(frame)
                }
                if (o.page == FlowPage.QUESTION && o.complete && o.answers.isNotEmpty()) {
                    val indices = o.options.mapIndexedNotNull { i, option -> if (option.text in o.answers) i else null }
                    listener.onAnswer(QuizModelResult(no,
                        if (indices.size > 1) QuestionType.MULTIPLE else QuestionType.SINGLE,
                        o.question, o.options.mapIndexed { i, option -> QuizOption(i, option.text, option.box) },
                        indices, o.confirm, o.next, o.confidence, answerSource = config.answerSource.name.lowercase()))
                }
                val action = policy.decide(o)
                error = null
                when (action) {
                    is FlowAction.Pause -> fail(action.reason)
                    FlowAction.Done -> { completionMessage = "已核实提交成功，运行结束"; stop() }
                    FlowAction.Wait -> schedule(token)
                    is FlowAction.Tap -> execute(token) { done -> gestures.tap(action.box, done) }
                    is FlowAction.Swipe -> execute(token) { done -> gestures.swipe(action.direction, action.navigation, done) }
                    is FlowAction.Confirm -> execute(token) { done -> gestures.confirm(action.box, action.attempt, done) }
                }
            } }
        } }
    }

    private fun execute(token: Long, action: ((Boolean) -> Unit) -> Unit) {
        if (!gestures.isForeground()) { fail("题目应用已离开前台"); return }
        phase = RunnerPhase.EXECUTING; publish()
        action { success -> synchronized(this) {
            if (token == generation) {
                if (success) schedule(token) else fail("点击或滑动失败，请检查无障碍权限")
            }
        } }
    }

    private fun schedule(token: Long) {
        phase = RunnerPhase.WAITING_PAGE_CHANGE; publish()
        task = scheduler.schedule(config.settleMs.toLong()) {
            synchronized(this) { if (token == generation && phase != RunnerPhase.STOPPED) observe() }
        }
    }
}
