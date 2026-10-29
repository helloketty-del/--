package com.futureyyy.tijing

import java.util.TreeMap

data class SweptAnswer(
    val questionNo: Int,
    val question: String,
    val type: QuestionType,
    val options: List<QuizOption>,
    val answerIndices: List<Int>,
    val answerText: String,
    val matchLabel: String?,
    val source: String?,
    val error: String?,
)

interface CaptureSweepListener {
    fun onNavigationError(message: String) = Unit
    fun onProgress(questionNo: Int, recognized: Int, pending: Int)
    fun onFinished(answers: List<SweptAnswer>)
}

/**
 * Capture sweep: screenshot -> fire model request without waiting -> tap the user-positioned
 * "next question" marker -> repeat. Answers are collected by question number and only shown
 * once the user stops and every in-flight request has come back.
 */
class CaptureSweep(
    private val captureGateway: CaptureGateway,
    private val modelGateway: ModelGateway,
    private val tapNext: ((Boolean) -> Unit) -> Unit,
    private val scheduler: RunnerScheduler,
    private val startingQuestionNo: Int,
    private val settleDelayMs: Long,
    private val listener: CaptureSweepListener,
) {
    private val lock = Any()
    private val answers = TreeMap<Int, SweptAnswer>()
    private var running = false
    private var finished = false
    private var questionNo = startingQuestionNo
    private var pending = 0
    private var drainPending = false
    private var captureFailures = 0
    private var lastSignature: FrameSignature? = null
    private var unchangedPolls = 0

    val isRunning: Boolean
        get() = synchronized(lock) { running }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            finished = false
            answers.clear()
            questionNo = startingQuestionNo
            pending = 0
            drainPending = false
            captureFailures = 0
            lastSignature = null
            unchangedPolls = 0
        }
        step()
    }

    fun stop() {
        val finishNow = synchronized(lock) {
            if (!running) return
            running = false
            if (pending == 0) {
                true
            } else {
                drainPending = true
                false
            }
        }
        captureGateway.cancel()
        if (finishNow) finish()
    }

    private fun step() {
        val currentNo = synchronized(lock) {
            if (!running) return
            questionNo
        }
        captureGateway.capture { result ->
            if (!synchronized(lock) { running }) {
                maybeFinish()
                return@capture
            }
            val frame = result.getOrNull()
            if (frame == null) {
                val exhausted = synchronized(lock) {
                    captureFailures += 1
                    captureFailures >= MAX_CAPTURE_FAILURES
                }
                if (exhausted) stop() else scheduler.schedule(settleDelayMs) { step() }
                return@capture
            }
            val unchanged = synchronized(lock) {
                captureFailures = 0
                val same = lastSignature == frame.signature
                lastSignature = frame.signature
                same
            }
            if (unchanged) {
                val polls = synchronized(lock) { ++unchangedPolls }
                if (polls < 3) scheduler.schedule(settleDelayMs) { step() }
                else {
                    listener.onNavigationError("页面没有变化：请调整点击/滑动位置或增加等待时间，先用动作测试验证")
                    stop()
                }
                return@capture
            }
            synchronized(lock) { unchangedPolls = 0 }
            requestModel(frame, currentNo)
            tapNext { success ->
                if (!synchronized(lock) { running }) return@tapNext
                if (!success) {
                    listener.onNavigationError("翻页动作未发出，请检查目标页面和无障碍权限")
                    stop()
                    return@tapNext
                }
                val advance = synchronized(lock) {
                    if (!running) {
                        false
                    } else {
                        questionNo += 1
                        true
                    }
                }
                if (advance) scheduler.schedule(settleDelayMs) { step() }
            }
        }
    }

    private fun requestModel(frame: CaptureFrame, no: Int, attempt: Int = 1) {
        if (attempt == 1) synchronized(lock) { pending += 1 }
        modelGateway.analyzeConcurrent(frame, no) { result ->
            val parsed = result.getOrNull()?.let { raw ->
                runCatching { QuizModelParser.parse(raw) }.getOrNull()
            }
            if (parsed == null && attempt < MAX_RECOGNITION_ATTEMPTS) {
                // The frame is already captured; retry against the same screenshot so a flaky
                // request does not cost the whole question.
                scheduler.schedule(RECOGNITION_RETRY_DELAY_MS) { requestModel(frame, no, attempt + 1) }
                return@analyzeConcurrent
            }
            val answer = SweptAnswer(
                questionNo = no,
                question = parsed?.question.orEmpty(),
                type = parsed?.type ?: QuestionType.SINGLE,
                options = parsed?.options.orEmpty(),
                answerIndices = parsed?.answerIndices.orEmpty(),
                answerText = parsed?.answerText.orEmpty(),
                matchLabel = parsed?.matchLabel,
                source = parsed?.answerSource,
                error = if (parsed == null) (result.exceptionOrNull()?.message ?: "识别失败") else null,
            )
            val recognized = synchronized(lock) {
                answers[no] = answer
                pending -= 1
                answers.size
            }
            val pendingNow = synchronized(lock) { pending }
            listener.onProgress(no, recognized, pendingNow)
            maybeFinish()
        }
    }

    private fun maybeFinish() {
        val finishNow = synchronized(lock) {
            if (finished || running || pending != 0 || !drainPending) {
                false
            } else {
                drainPending = false
                true
            }
        }
        if (finishNow) finish()
    }

    private fun finish() {
        val snapshot = synchronized(lock) {
            if (finished) return
            finished = true
            running = false
            answers.values.toList()
        }
        listener.onFinished(snapshot)
    }

    companion object {
        const val MAX_CAPTURE_FAILURES = 5
        const val MAX_RECOGNITION_ATTEMPTS = 3
        const val RECOGNITION_RETRY_DELAY_MS = 800L
    }
}
