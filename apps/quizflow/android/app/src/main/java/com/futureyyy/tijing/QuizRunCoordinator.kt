package com.futureyyy.tijing

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class RunnerPhase {
    READY,
    CAPTURING,
    RECOGNIZING,
    SHOWING_ANSWER,
    EXECUTING,
    WAITING_PAGE_CHANGE,
    PAUSED_ERROR,
    STOPPED,
}

data class RunnerSnapshot(
    val phase: RunnerPhase,
    val questionNo: Int,
    val mode: QuizMode,
    val errorMessage: String? = null,
    val attemptCount: Int = 0,
    val maxAttempts: Int = 3,
    val elapsedMs: Long = 0L,
    val matchStatus: String? = null,
    val matchLabel: String? = null,
    val answerSource: String? = null,
    val message: String? = null,
)

interface CaptureGateway {
    fun capture(callback: (Result<CaptureFrame>) -> Unit)
    fun cancel() = Unit
}

interface ModelGateway {
    fun analyze(frame: CaptureFrame, questionNo: Int, callback: (Result<String>) -> Unit)

    /** Concurrent variant: the caller owns ordering, so an in-flight request is never cancelled. */
    fun analyzeConcurrent(frame: CaptureFrame, questionNo: Int, callback: (Result<String>) -> Unit) =
        analyze(frame, questionNo, callback)

    fun cancel() = Unit
}

interface GestureGateway {
    fun isTargetForeground(): Boolean
    fun targetUnavailableMessage(): String = "请打开题目页"
    fun resetTarget() = Unit
    fun tap(box: NormalizedBox, callback: (Boolean) -> Unit)
    fun tapConfirm(box: NormalizedBox?, callback: (Boolean) -> Unit)
    fun tapConfirmAttempt(box: NormalizedBox?, attempt: Int, callback: (Boolean) -> Unit) = tapConfirm(box, callback)
    fun cancel()
}

interface RunnerListener {
    fun onSnapshot(snapshot: RunnerSnapshot)
    fun onAnswer(result: QuizModelResult)
    fun onError(message: String)
}

fun interface RunnerScheduledTask {
    fun cancel()
}

fun interface RunnerScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): RunnerScheduledTask

    companion object {
        val SYSTEM: RunnerScheduler = ExecutorRunnerScheduler
    }
}

private object ExecutorRunnerScheduler : RunnerScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "QuizRunCoordinator").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, action: () -> Unit): RunnerScheduledTask {
        val future = executor.schedule({ action() }, delayMs, TimeUnit.MILLISECONDS)
        return RunnerScheduledTask { future.cancel(false) }
    }
}

private enum class AutoCheckpointStage {
    ANSWERS,
    CONFIRM_BASELINE,
    CONFIRM,
    PAGE_POLL,
}

class QuizRunCoordinator(
    private val mode: QuizMode,
    private val startingQuestionNo: Int = 1,
    private val captureGateway: CaptureGateway,
    private val modelGateway: ModelGateway,
    private val gestureGateway: GestureGateway,
    private val listener: RunnerListener,
    private val scheduler: RunnerScheduler = RunnerScheduler.SYSTEM,
) : QuizRunner {
    private var phase = RunnerPhase.READY
    private var questionNo = startingQuestionNo
    private var errorMessage: String? = null
    private var generation = 0L
    private var automaticRunStarted = false
    private var baselineSignature: FrameSignature? = null
    private var lastManualSignature: FrameSignature? = null
    private var pendingManualSignature: FrameSignature? = null
    private var pendingActionPlan: QuizActionPlan? = null
    private var autoCheckpointStage: AutoCheckpointStage? = null
    private var nextAnswerPosition = 0
    private var confirmTapCompleted = false
    private var confirmTapAttempts = 0
    private var stableCandidate: FrameSignature? = null
    private var pollAttempts = 0
    private var questionStartedAtMs = 0L
    private var modelAttemptCount = 0
    private var latestResult: QuizModelResult? = null
    private val scheduledTasks = mutableListOf<RunnerScheduledTask>()

    init {
        require(startingQuestionNo > 0) { "startingQuestionNo 必须大于 0" }
    }

    @Synchronized
    override fun onPrimaryAction() {
        if (phase != RunnerPhase.READY) return
        if (mode == QuizMode.AUTO) automaticRunStarted = true
        beginQuestion()
    }

    @Synchronized
    override fun pause() {
        invalidateCurrentWork()
        cancelGestures()
        if (phase == RunnerPhase.STOPPED) return
        errorMessage = "已暂停"
        transition(RunnerPhase.PAUSED_ERROR)
    }

    @Synchronized
    override fun resume() {
        restartPausedRun()
    }

    @Synchronized
    override fun retry() {
        restartPausedRun()
    }

    @Synchronized
    override fun stop() {
        invalidateCurrentWork()
        automaticRunStarted = false
        clearAutoCheckpoint()
        cancelGestures()
        errorMessage = null
        if (phase != RunnerPhase.STOPPED) transition(RunnerPhase.STOPPED)
    }

    @Synchronized
    override fun snapshot(): RunnerSnapshot = snapshotUnsafe()

    private fun restartPausedRun() {
        if (phase != RunnerPhase.PAUSED_ERROR) return
        errorMessage = null
        transition(RunnerPhase.READY)
        if (phase != RunnerPhase.READY) return
        if (mode == QuizMode.AUTO) {
            automaticRunStarted = true
            if (pendingActionPlan != null && autoCheckpointStage != null) {
                if (autoCheckpointStage == AutoCheckpointStage.CONFIRM && !confirmTapCompleted) {
                    confirmTapAttempts = 0
                }
                resumeAutoCheckpoint()
                return
            }
        }
        beginQuestion()
    }

    private fun resumeAutoCheckpoint() {
        invalidateCurrentWork()
        val runGeneration = generation
        cancelGestures()
        if (!ensureForeground(runGeneration)) return
        when (autoCheckpointStage) {
            AutoCheckpointStage.ANSWERS,
            AutoCheckpointStage.CONFIRM_BASELINE,
            AutoCheckpointStage.CONFIRM,
            -> {
                transition(RunnerPhase.EXECUTING)
                if (isCurrent(runGeneration, RunnerPhase.EXECUTING)) {
                    resumePendingActions(runGeneration)
                }
            }

            AutoCheckpointStage.PAGE_POLL -> restartPagePolling(runGeneration)
            null -> beginQuestion()
        }
    }

    private fun beginQuestion() {
        invalidateCurrentWork()
        val runGeneration = generation
        errorMessage = null
        baselineSignature = null
        stableCandidate = null
        pollAttempts = 0
        questionStartedAtMs = System.currentTimeMillis()
        modelAttemptCount = 0
        latestResult = null
        gestureGateway.resetTarget()

        transition(RunnerPhase.CAPTURING)
        if (!isCurrent(runGeneration, RunnerPhase.CAPTURING)) return
        if (mode == QuizMode.AUTO) {
            cancelGestures()
            captureWhenForegroundReady(runGeneration)
        } else {
            requestQuestionCapture(runGeneration)
        }
    }

    @Synchronized
    private fun captureWhenForegroundReady(runGeneration: Long, retryAttempt: Int = 0) {
        if (!isCurrent(runGeneration, RunnerPhase.CAPTURING)) return
        val foreground = readTargetForeground(runGeneration) ?: return
        if (foreground) {
            requestQuestionCapture(runGeneration)
            return
        }
        if (retryAttempt >= MAX_START_FOREGROUND_RETRIES) {
            fail(runGeneration, gestureGateway.targetUnavailableMessage())
            return
        }
        val task = try {
            scheduler.schedule(START_FOREGROUND_RETRY_DELAY_MS) {
                captureWhenForegroundReady(runGeneration, retryAttempt + 1)
            }
        } catch (error: Exception) {
            fail(runGeneration, errorMessage(error, "前台应用检查调度失败"))
            return
        }
        if (isCurrent(runGeneration, RunnerPhase.CAPTURING)) {
            scheduledTasks += task
        } else {
            task.cancel()
        }
    }

    private fun requestQuestionCapture(runGeneration: Long) {
        val delivered = AtomicBoolean(false)
        try {
            captureGateway.capture { result ->
                if (delivered.compareAndSet(false, true)) onQuestionCaptured(runGeneration, result)
            }
        } catch (error: Exception) {
            if (delivered.compareAndSet(false, true)) fail(runGeneration, errorMessage(error, "截图失败"))
        }
    }

    @Synchronized
    private fun onQuestionCaptured(runGeneration: Long, result: Result<CaptureFrame>) {
        if (!isCurrent(runGeneration, RunnerPhase.CAPTURING)) return
        val frame = result.getOrElse { error ->
            fail(runGeneration, errorMessage(error, "截图失败"))
            return
        }
        if (mode == QuizMode.MANUAL && !prepareManualFrame(runGeneration, frame.signature)) return
        baselineSignature = frame.signature
        transition(RunnerPhase.RECOGNIZING)
        if (!isCurrent(runGeneration, RunnerPhase.RECOGNIZING)) return
        requestModel(runGeneration, frame)
    }

    private fun requestModel(runGeneration: Long, frame: CaptureFrame) {
        modelAttemptCount += 1
        val delivered = AtomicBoolean(false)
        try {
            modelGateway.analyze(frame, questionNo) { result ->
                if (delivered.compareAndSet(false, true)) {
                    onModelCompleted(runGeneration, frame.signature, result)
                }
            }
        } catch (error: Exception) {
            if (delivered.compareAndSet(false, true)) fail(runGeneration, errorMessage(error, "模型识别失败"))
        }
    }

    @Synchronized
    private fun onModelCompleted(
        runGeneration: Long,
        recognizedSignature: FrameSignature,
        result: Result<String>,
    ) {
        if (!isCurrent(runGeneration, RunnerPhase.RECOGNIZING)) return
        val raw = result.getOrElse { error ->
            fail(runGeneration, errorMessage(error, "模型识别失败"))
            return
        }
        val parsed = try {
            QuizModelParser.parse(raw)
        } catch (error: Exception) {
            fail(runGeneration, errorMessage(error, "模型响应解析失败"))
            return
        }

        if (mode == QuizMode.MANUAL) {
            lastManualSignature = recognizedSignature
            pendingManualSignature = null
            transition(RunnerPhase.SHOWING_ANSWER)
            notifyAnswer(parsed)
            if (isCurrent(runGeneration, RunnerPhase.SHOWING_ANSWER)) {
                transition(RunnerPhase.READY)
            }
            return
        }

        val plan = try {
            QuizModelParser.actionPlan(parsed, questionNo)
        } catch (error: Exception) {
            fail(runGeneration, errorMessage(error, "答案动作无效"))
            return
        }
        replaceAutoCheckpoint(plan)
        transition(RunnerPhase.SHOWING_ANSWER)
        notifyAnswer(parsed)
        if (!isCurrent(runGeneration, RunnerPhase.SHOWING_ANSWER)) return
        transition(RunnerPhase.EXECUTING)
        if (isCurrent(runGeneration, RunnerPhase.EXECUTING)) resumePendingActions(runGeneration)
    }

    private fun prepareManualFrame(runGeneration: Long, signature: FrameSignature): Boolean {
        val pending = pendingManualSignature
        if (pending != null && pending.hammingDistance(signature) <= MANUAL_SAME_PAGE_DISTANCE) {
            pendingManualSignature = signature
            return true
        }

        val previous = lastManualSignature
        if (previous != null) {
            if (previous.hammingDistance(signature) <= MANUAL_SAME_PAGE_DISTANCE) {
                fail(runGeneration, MANUAL_SAME_PAGE_MESSAGE)
                return false
            }
            questionNo += 1
        }
        pendingManualSignature = signature
        return true
    }

    private fun resumePendingActions(runGeneration: Long) {
        val plan = pendingActionPlan
            ?: run {
                fail(runGeneration, "缺少待执行答案")
                return
            }
        when (autoCheckpointStage) {
            AutoCheckpointStage.ANSWERS -> executeAnswer(runGeneration, plan)
            AutoCheckpointStage.CONFIRM_BASELINE -> captureConfirmBaseline(runGeneration, plan)
            AutoCheckpointStage.CONFIRM -> {
                if (confirmTapCompleted) {
                    beginPagePolling(runGeneration)
                } else {
                    executeConfirm(runGeneration, plan)
                }
            }

            AutoCheckpointStage.PAGE_POLL -> restartPagePolling(runGeneration)
            null -> fail(runGeneration, "缺少执行检查点")
        }
    }

    private fun executeAnswer(runGeneration: Long, plan: QuizActionPlan) {
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return
        if (nextAnswerPosition >= plan.answerOptions.size) {
            autoCheckpointStage = AutoCheckpointStage.CONFIRM_BASELINE
            captureConfirmBaseline(runGeneration, plan)
            return
        }
        if (!ensureForeground(runGeneration)) return
        val box = plan.answerOptions[nextAnswerPosition].box
            ?: run {
                fail(runGeneration, "答案选项缺少有效 box")
                return
            }
        val delivered = AtomicBoolean(false)
        try {
            gestureGateway.tap(box) { success ->
                if (delivered.compareAndSet(false, true)) {
                    onAnswerTapCompleted(runGeneration, plan, success)
                }
            }
        } catch (error: Exception) {
            if (delivered.compareAndSet(false, true)) fail(runGeneration, errorMessage(error, "点击答案失败"))
        }
    }

    @Synchronized
    private fun onAnswerTapCompleted(
        runGeneration: Long,
        plan: QuizActionPlan,
        success: Boolean,
    ) {
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return
        if (!success) {
            fail(runGeneration, "点击答案失败")
            return
        }
        nextAnswerPosition += 1
        executeAnswer(runGeneration, plan)
    }

    private fun captureConfirmBaseline(runGeneration: Long, plan: QuizActionPlan) {
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return
        if (!ensureForeground(runGeneration)) return
        val delivered = AtomicBoolean(false)
        try {
            captureGateway.capture { result ->
                if (delivered.compareAndSet(false, true)) {
                    onConfirmBaselineCaptured(runGeneration, plan, result)
                }
            }
        } catch (error: Exception) {
            if (delivered.compareAndSet(false, true)) {
                fail(runGeneration, errorMessage(error, "确认前截图失败"))
            }
        }
    }

    @Synchronized
    private fun onConfirmBaselineCaptured(
        runGeneration: Long,
        plan: QuizActionPlan,
        result: Result<CaptureFrame>,
    ) {
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return
        if (autoCheckpointStage != AutoCheckpointStage.CONFIRM_BASELINE) return
        val frame = result.getOrElse { error ->
            fail(runGeneration, errorMessage(error, "确认前截图失败"))
            return
        }
        baselineSignature = frame.signature
        autoCheckpointStage = AutoCheckpointStage.CONFIRM
        executeConfirm(runGeneration, plan)
    }

    private fun executeConfirm(runGeneration: Long, plan: QuizActionPlan) {
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return
        if (confirmTapAttempts >= MAX_CONFIRM_TAP_ATTEMPTS) {
            fail(runGeneration, "点击确认失败")
            return
        }
        confirmTapAttempts += 1
        val foreground = try {
            gestureGateway.isTargetForeground()
        } catch (error: Exception) {
            fail(runGeneration, errorMessage(error, "前台应用检查失败"))
            return
        }
        if (!foreground) {
            retryConfirm(runGeneration, plan)
            return
        }
        val delivered = AtomicBoolean(false)
        try {
            gestureGateway.tapConfirmAttempt(plan.confirmBox, confirmTapAttempts) { success ->
                if (delivered.compareAndSet(false, true)) onConfirmTapCompleted(runGeneration, success)
            }
        } catch (error: Exception) {
            if (delivered.compareAndSet(false, true)) retryConfirm(runGeneration, plan)
        }
    }

    @Synchronized
    private fun onConfirmTapCompleted(runGeneration: Long, success: Boolean) {
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return
        if (!success) {
            retryConfirm(runGeneration, pendingActionPlan)
            return
        }
        confirmTapCompleted = true
        autoCheckpointStage = AutoCheckpointStage.PAGE_POLL
        beginPagePolling(runGeneration)
    }

    private fun retryConfirm(runGeneration: Long, plan: QuizActionPlan?) {
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return
        val pendingPlan = plan ?: run {
            fail(runGeneration, "缺少待执行答案")
            return
        }
        if (confirmTapAttempts >= MAX_CONFIRM_TAP_ATTEMPTS) {
            fail(runGeneration, "点击确认失败")
            return
        }
        val task = try {
            scheduler.schedule(CONFIRM_RETRY_DELAY_MS) {
                executeConfirm(runGeneration, pendingPlan)
            }
        } catch (error: Exception) {
            fail(runGeneration, errorMessage(error, "确认重试调度失败"))
            return
        }
        if (isCurrent(runGeneration, RunnerPhase.EXECUTING)) {
            scheduledTasks += task
        } else {
            task.cancel()
        }
    }

    private fun beginPagePolling(runGeneration: Long) {
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return
        autoCheckpointStage = AutoCheckpointStage.PAGE_POLL
        stableCandidate = null
        pollAttempts = 0
        transition(RunnerPhase.WAITING_PAGE_CHANGE)
        if (isCurrent(runGeneration, RunnerPhase.WAITING_PAGE_CHANGE)) {
            schedulePoll(runGeneration, FIRST_POLL_DELAY_MS)
        }
    }

    private fun restartPagePolling(runGeneration: Long) {
        if (generation != runGeneration) return
        stableCandidate = null
        pollAttempts = 0
        transition(RunnerPhase.WAITING_PAGE_CHANGE)
        if (isCurrent(runGeneration, RunnerPhase.WAITING_PAGE_CHANGE)) {
            schedulePoll(runGeneration, FIRST_POLL_DELAY_MS)
        }
    }

    private fun schedulePoll(
        runGeneration: Long,
        delayMs: Long,
        captureAttempt: Int = 1,
    ) {
        if (!isCurrent(runGeneration, RunnerPhase.WAITING_PAGE_CHANGE)) return
        val task = try {
            scheduler.schedule(delayMs) { runPagePoll(runGeneration, captureAttempt) }
        } catch (error: Exception) {
            fail(runGeneration, errorMessage(error, "换页检测调度失败"))
            return
        }
        if (isCurrent(runGeneration, RunnerPhase.WAITING_PAGE_CHANGE)) {
            scheduledTasks += task
        } else {
            task.cancel()
        }
    }

    @Synchronized
    private fun runPagePoll(runGeneration: Long, captureAttempt: Int = 1) {
        if (!isCurrent(runGeneration, RunnerPhase.WAITING_PAGE_CHANGE)) return
        if (!ensureForeground(runGeneration)) return
        val delivered = AtomicBoolean(false)
        try {
            captureGateway.capture { result ->
                if (delivered.compareAndSet(false, true)) {
                    onPollCaptured(runGeneration, result, captureAttempt)
                }
            }
        } catch (error: Exception) {
            if (delivered.compareAndSet(false, true)) {
                onPollCaptured(runGeneration, Result.failure(error), captureAttempt)
            }
        }
    }

    @Synchronized
    private fun onPollCaptured(
        runGeneration: Long,
        result: Result<CaptureFrame>,
        captureAttempt: Int,
    ) {
        if (!isCurrent(runGeneration, RunnerPhase.WAITING_PAGE_CHANGE)) return
        val frame = result.getOrElse { error ->
            if (mode == QuizMode.AUTO && captureAttempt < MAX_AUTO_CAPTURE_ATTEMPTS) {
                schedulePoll(runGeneration, CAPTURE_RETRY_DELAY_MS, captureAttempt + 1)
            } else {
                fail(runGeneration, errorMessage(error, "换页截图失败"))
            }
            return
        }
        pollAttempts += 1
        val baseline = baselineSignature
            ?: run {
                fail(runGeneration, "缺少换页基线")
                return
            }
        val changedEnough = baseline.hammingDistance(frame.signature) >= PAGE_CHANGE_DISTANCE
        if (changedEnough) {
            val previous = stableCandidate
            if (previous != null && previous.hammingDistance(frame.signature) <= STABLE_FRAME_DISTANCE) {
                advanceAfterPageChange(runGeneration)
                return
            }
            stableCandidate = frame.signature
        } else {
            stableCandidate = null
        }

        if (pollAttempts >= MAX_POLL_ATTEMPTS) {
            if (!retryConfirmAfterUnchangedPage(runGeneration)) {
                fail(runGeneration, "页面长时间未变化")
            }
        } else {
            schedulePoll(runGeneration, SUBSEQUENT_POLL_DELAY_MS)
        }
    }

    private fun retryConfirmAfterUnchangedPage(runGeneration: Long): Boolean {
        if (mode != QuizMode.AUTO) return false
        val plan = pendingActionPlan ?: return false

        // The accessibility callback confirms that Android dispatched the gesture, not that
        // the target accepted it. Keep the selected answers and retry only the confirmation.
        confirmTapCompleted = false
        autoCheckpointStage = AutoCheckpointStage.CONFIRM
        stableCandidate = null
        pollAttempts = 0
        if (confirmTapAttempts >= MAX_CONFIRM_TAP_ATTEMPTS) return false
        transition(RunnerPhase.EXECUTING)
        if (!isCurrent(runGeneration, RunnerPhase.EXECUTING)) return true
        retryConfirm(runGeneration, plan)
        return true
    }

    private fun advanceAfterPageChange(runGeneration: Long) {
        if (!isCurrent(runGeneration, RunnerPhase.WAITING_PAGE_CHANGE)) return
        clearAutoCheckpoint()
        questionNo += 1
        transition(RunnerPhase.READY)
        if (
            mode == QuizMode.AUTO && automaticRunStarted &&
            isCurrent(runGeneration, RunnerPhase.READY)
        ) {
            beginQuestion()
        }
    }

    private fun replaceAutoCheckpoint(plan: QuizActionPlan) {
        pendingActionPlan = plan
        autoCheckpointStage = AutoCheckpointStage.ANSWERS
        nextAnswerPosition = 0
        confirmTapCompleted = false
        confirmTapAttempts = 0
    }

    private fun clearAutoCheckpoint() {
        pendingActionPlan = null
        autoCheckpointStage = null
        nextAnswerPosition = 0
        confirmTapCompleted = false
        confirmTapAttempts = 0
    }

    private fun ensureForeground(runGeneration: Long): Boolean {
        if (mode != QuizMode.AUTO) return true
        val foreground = readTargetForeground(runGeneration) ?: return false
        if (!foreground) fail(runGeneration, gestureGateway.targetUnavailableMessage())
        return foreground
    }

    private fun readTargetForeground(runGeneration: Long): Boolean? = try {
        gestureGateway.isTargetForeground()
    } catch (error: Exception) {
        fail(runGeneration, errorMessage(error, "前台应用检查失败"))
        null
    }

    private fun fail(runGeneration: Long, message: String) {
        if (generation != runGeneration || phase == RunnerPhase.STOPPED) return
        invalidateCurrentWork()
        if (mode == QuizMode.AUTO) cancelGestures()
        errorMessage = message
        transition(RunnerPhase.PAUSED_ERROR)
        try {
            listener.onError(message)
        } catch (_: Exception) {
            // Listener failures do not resume or advance the run.
        }
    }

    private fun invalidateCurrentWork() {
        generation += 1
        try {
            captureGateway.cancel()
        } catch (_: Exception) {
            // Generation checks still make a late capture callback inert.
        }
        try {
            modelGateway.cancel()
        } catch (_: Exception) {
            // Generation checks still make a late model callback inert.
        }
        scheduledTasks.forEach { task ->
            try {
                task.cancel()
            } catch (_: Exception) {
                // Generation checks still make already-dispatched tasks inert.
            }
        }
        scheduledTasks.clear()
    }

    private fun cancelGestures() {
        try {
            gestureGateway.cancel()
        } catch (_: Exception) {
            // State invalidation prevents late gesture callbacks from advancing.
        }
    }

    private fun transition(next: RunnerPhase) {
        phase = next
        val snapshot = snapshotUnsafe()
        try {
            listener.onSnapshot(snapshot)
        } catch (_: Exception) {
            // UI listeners are observers and do not own state progression.
        }
    }

    private fun notifyAnswer(result: QuizModelResult) {
        latestResult = result
        try {
            listener.onAnswer(result)
        } catch (_: Exception) {
            // UI listeners are observers and do not own state progression.
        }
    }

    private fun isCurrent(runGeneration: Long, expectedPhase: RunnerPhase): Boolean =
        generation == runGeneration && phase == expectedPhase

    private fun snapshotUnsafe() = RunnerSnapshot(
        phase = phase,
        questionNo = questionNo,
        mode = mode,
        errorMessage = errorMessage,
        attemptCount = modelAttemptCount,
        maxAttempts = MAX_AUTO_CAPTURE_ATTEMPTS,
        elapsedMs = if (questionStartedAtMs == 0L) 0L
        else (System.currentTimeMillis() - questionStartedAtMs).coerceAtLeast(0L),
        matchStatus = latestResult?.matchStatus,
        matchLabel = latestResult?.matchLabel,
        answerSource = latestResult?.answerSource,
        message = when (phase) {
            RunnerPhase.CAPTURING -> "正在截图"
            RunnerPhase.RECOGNIZING -> "正在识别"
            RunnerPhase.SHOWING_ANSWER -> "已得到最终选项"
            RunnerPhase.EXECUTING -> "正在执行答案"
            RunnerPhase.WAITING_PAGE_CHANGE -> "等待进入下一题"
            RunnerPhase.PAUSED_ERROR -> errorMessage
            else -> null
        },
    )

    private fun errorMessage(error: Throwable, fallback: String): String =
        error.message?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        const val FIRST_POLL_DELAY_MS = 650L
        const val SUBSEQUENT_POLL_DELAY_MS = 450L
        const val CAPTURE_RETRY_DELAY_MS = 500L
        const val START_FOREGROUND_RETRY_DELAY_MS = 250L
        const val MAX_START_FOREGROUND_RETRIES = 4
        const val CONFIRM_RETRY_DELAY_MS = 350L
        const val MAX_CONFIRM_TAP_ATTEMPTS = 3
        const val MAX_AUTO_CAPTURE_ATTEMPTS = 3
        const val MAX_POLL_ATTEMPTS = 12
        const val PAGE_CHANGE_DISTANCE = 18
        const val STABLE_FRAME_DISTANCE = 8
        const val MANUAL_SAME_PAGE_DISTANCE = 8
        const val MANUAL_SAME_PAGE_MESSAGE = "页面似乎未进入下一题，请确认后重试"
    }
}
