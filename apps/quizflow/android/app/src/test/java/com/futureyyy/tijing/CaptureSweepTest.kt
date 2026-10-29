package com.futureyyy.tijing

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class CaptureSweepTest {
    private fun frame(seed: Int) = CaptureFrame("image$seed", FrameSignature.fromBits(BooleanArray(FrameSignature.BIT_COUNT) { it < seed }))
    private inner class Fixture(val changePage: Boolean = false, val failNavigation: Boolean = false, val deferNavigation: Boolean = false) {
        var captures = 0; var models = 0; var navigations = 0
        val errors = mutableListOf<String>(); val tasks = ArrayDeque<() -> Unit>()
        var pendingNavigation: ((Boolean) -> Unit)? = null
        val sweep = CaptureSweep(
            object : CaptureGateway {
                override fun capture(callback: (Result<CaptureFrame>) -> Unit) {
                    captures++; callback(Result.success(frame(if (changePage) captures else 1)))
                }
            },
            object : ModelGateway {
                override fun analyze(frame: CaptureFrame, questionNo: Int, callback: (Result<String>) -> Unit) {
                    models++; callback(Result.success("""{"question_no":$questionNo,"question_type":"single","question":"题干","options":[{"index":0,"text":"答案","box":null}],"answer_indices":[0],"confidence":0.99}"""))
                }
            },
            { done -> navigations++; if (deferNavigation) pendingNavigation = done else done(!failNavigation) },
            RunnerScheduler { _, action -> tasks.add(action); RunnerScheduledTask {} }, 1, 1000L,
            object : CaptureSweepListener {
                override fun onNavigationError(message: String) { errors.add(message) }
                override fun onProgress(questionNo: Int, recognized: Int, pending: Int) = Unit
                override fun onFinished(answers: List<SweptAnswer>) = Unit
            },
        )
    }

    @Test fun `failed gesture stops collection without advancing to another screenshot`() {
        val f = Fixture(failNavigation = true); f.sweep.start()
        assertFalse(f.sweep.isRunning); assertEquals(1, f.captures); assertEquals(1, f.models)
        assertEquals(1, f.errors.size)
    }

    @Test fun `unchanged page is polled before stopping without duplicate model requests`() {
        val f = Fixture(); f.sweep.start()
        while (f.tasks.isNotEmpty()) f.tasks.removeFirst().invoke()
        assertFalse(f.sweep.isRunning); assertEquals(4, f.captures); assertEquals(1, f.models)
        assertEquals(1, f.navigations); assertTrue(f.errors.single().contains("页面没有变化"))
    }

    @Test fun `changed page allows the next question to be captured`() {
        val f = Fixture(changePage = true); f.sweep.start(); f.tasks.removeFirst().invoke(); f.sweep.stop()
        assertEquals(2, f.models); assertEquals(2, f.navigations); assertTrue(f.errors.isEmpty())
    }

    @Test fun `late gesture result after stop does not restart collection`() {
        val f = Fixture(deferNavigation = true); f.sweep.start(); f.sweep.stop(); f.pendingNavigation!!.invoke(true)
        assertTrue(f.tasks.isEmpty()); assertEquals(1, f.captures)
    }
}
