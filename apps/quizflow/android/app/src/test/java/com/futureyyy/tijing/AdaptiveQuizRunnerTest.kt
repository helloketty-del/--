package com.futureyyy.tijing

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class AdaptiveQuizRunnerTest {
    private val box = NormalizedBox(10f, 40f, 70f, 8f)
    private val next = NormalizedBox(80f, 85f, 15f, 8f)
    private fun q(key: String, selected: Boolean = false, last: Boolean = false) = FlowObservation(
        FlowPage.QUESTION, 0.99, key, "题干$key", true,
        listOf(FlowOption("答案$key", box, selected)), listOf("答案$key"), next = if (last) null else next,
        submit = if (last) next else null, last = last, unanswered = if (last) 0 else null,
    )

    private inner class Fixture {
        val queue = ArrayDeque<() -> Unit>()
        val observations = ArrayDeque<FlowObservation>()
        val actions = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var foreground = true
        var defer = false
        var pending: ((Result<FlowObservation>) -> Unit)? = null
        val runner = AdaptiveQuizRunner(
            QuizRuntimeConfig.fromJson("""{"endpoint":"https://example.test/v1","model":"vision","apiKey":"test","mode":"auto","autoSubmit":true}"""),
            object : CaptureGateway {
                override fun capture(callback: (Result<CaptureFrame>) -> Unit) = callback(Result.success(
                    CaptureFrame("data:image/jpeg;base64,TEST", FrameSignature.fromBits(BooleanArray(FrameSignature.BIT_COUNT)))))
            },
            object : FlowModel {
                override fun cancel() = Unit
                override fun observe(frames: List<CaptureFrame>, questionNo: Int, context: String, callback: (Result<FlowObservation>) -> Unit) {
                    if (defer) pending = callback else callback(Result.success(observations.removeFirst()))
                }
            },
            object : FlowGestures {
                override fun isForeground() = foreground
                override fun tap(box: NormalizedBox, callback: (Boolean) -> Unit) { actions.add("tap:$box"); callback(true) }
                override fun swipe(direction: FlowSwipe, callback: (Boolean) -> Unit) { actions.add("swipe:$direction"); callback(true) }
                override fun cancel() = Unit
            },
            object : RunnerListener {
                override fun onSnapshot(snapshot: RunnerSnapshot) = Unit
                override fun onAnswer(result: QuizModelResult) = Unit
                override fun onError(message: String) { errors.add(message) }
            },
            RunnerScheduler { _, action ->
                var cancelled = false
                queue.add { if (!cancelled) action() }
                RunnerScheduledTask { cancelled = true }
            },
        )
    }

    @Test fun `two questions submit dialog result complete without extra actions`() {
        val f = Fixture()
        f.observations.addAll(listOf(q("1"), q("1", true), q("2", last = true), q("2", true, true),
            FlowObservation(FlowPage.SUBMIT_DIALOG, 0.99, submitConfirm = next, unanswered = 0),
            FlowObservation(FlowPage.RESULT, 0.99, submissionSucceeded = true)))
        f.runner.onPrimaryAction()
        while (f.queue.isNotEmpty()) f.queue.removeFirst().invoke()
        assertEquals(5, f.actions.size)
        assertTrue(f.errors.isEmpty())
        assertEquals(RunnerPhase.STOPPED, f.runner.snapshot().phase)
        assertEquals("已核实提交成功，运行结束", f.runner.snapshot().message)
    }

    @Test fun `late model callback after stop cannot tap`() {
        val f = Fixture(); f.defer = true
        f.runner.onPrimaryAction()
        f.runner.stop()
        f.pending!!.invoke(Result.success(q("1")))
        assertTrue(f.actions.isEmpty())
        assertEquals(RunnerPhase.STOPPED, f.runner.snapshot().phase)
    }

    @Test fun `late model callback after pause cannot tap`() {
        val f = Fixture(); f.defer = true
        f.runner.onPrimaryAction(); f.runner.pause()
        f.pending!!.invoke(Result.success(q("1")))
        assertTrue(f.actions.isEmpty())
        assertEquals(RunnerPhase.PAUSED_ERROR, f.runner.snapshot().phase)
    }

    @Test fun `foreground loss between analysis and action pauses`() {
        val f = Fixture(); f.defer = true
        f.runner.onPrimaryAction(); f.foreground = false
        f.pending!!.invoke(Result.success(q("1")))
        assertTrue(f.actions.isEmpty())
        assertEquals(RunnerPhase.PAUSED_ERROR, f.runner.snapshot().phase)
    }

    @Test fun `model request failure pauses without gesture`() {
        val f = Fixture(); f.defer = true
        f.runner.onPrimaryAction()
        f.pending!!.invoke(Result.failure(IllegalStateException("连接失败")))
        assertTrue(f.actions.isEmpty())
        assertEquals("连接失败", f.runner.snapshot().errorMessage)
    }
}
