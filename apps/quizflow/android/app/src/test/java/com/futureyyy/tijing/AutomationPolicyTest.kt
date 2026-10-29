package com.futureyyy.tijing

import org.junit.Assert.*
import org.junit.Test

class AutomationPolicyTest {
    private val a = NormalizedBox(10f, 30f, 70f, 8f)
    private val b = NormalizedBox(10f, 45f, 70f, 8f)
    private val next = NormalizedBox(80f, 85f, 15f, 8f)
    private fun question(key: String = "1", selected: Boolean? = false) = FlowObservation(
        FlowPage.QUESTION, 0.99, key, "题干", true,
        listOf(FlowOption("答案甲", a, selected), FlowOption("答案乙", b, false)), listOf("答案甲"), next = next,
    )

    @Test fun `confirmation delivery without page change retries a different attempt`() {
        val p = AutomationPolicy(navigation = "confirm")
        val o = question(selected = true).copy(confirm = b)
        assertEquals(FlowAction.Confirm(b, 1), p.decide(o))
        assertEquals(FlowAction.Confirm(b, 2), p.decide(o))
        assertEquals(FlowAction.Confirm(b, 3), p.decide(o))
        assertTrue(p.decide(o) is FlowAction.Pause)
    }

    @Test fun `confirmation retry uses freshly observed button box`() {
        val p = AutomationPolicy(navigation = "confirm")
        p.decide(question(selected = true).copy(confirm = b))
        assertEquals(FlowAction.Confirm(next, 2), p.decide(question(selected = true).copy(confirm = next)))
    }

    @Test fun `after confirmation page changes the runner can answer next question`() {
        val p = AutomationPolicy(navigation = "confirm")
        p.decide(question(selected = true).copy(confirm = b))
        assertEquals(FlowAction.Tap(a, "选择答案"), p.decide(question("2")))
    }

    @Test fun `last question confirmation is completed before final submission`() {
        val p = AutomationPolicy(navigation = "confirm", autoSubmit = true)
        val last = question(selected = true).copy(last = true, confirm = b, submit = next, unanswered = 0)
        assertEquals(FlowAction.Confirm(b, 1), p.decide(last))
        assertEquals(FlowAction.Tap(next, "提交全部答案"), p.decide(last.copy(confirm = null)))
    }

    @Test fun `tap answer then screen swipe then next question`() {
        val p = AutomationPolicy(navigation = "up")
        assertEquals(FlowAction.Tap(a, "选择答案"), p.decide(question()))
        assertEquals(FlowAction.Swipe(FlowSwipe.UP, true), p.decide(question(selected = true)))
        assertEquals(FlowAction.Tap(a, "选择答案"), p.decide(question("2")))
    }

    @Test fun `reordered options use text instead of letter`() {
        val p = AutomationPolicy(navigation = "up")
        val o = question().copy(options = listOf(FlowOption("答案乙", a, false), FlowOption("答案甲", b, false)))
        assertEquals(FlowAction.Tap(b, "选择答案"), p.decide(o))
    }

    @Test fun `long stem scrolls before answering`() {
        val p = AutomationPolicy(navigation = "up")
        assertEquals(FlowAction.Swipe(FlowSwipe.UP), p.decide(question().copy(complete = false)))
        assertEquals(FlowAction.Tap(a, "选择答案"), p.decide(question()))
    }

    @Test fun `hidden answer coordinates never used`() {
        val p = AutomationPolicy(navigation = "up")
        assertEquals(FlowAction.Swipe(FlowSwipe.DOWN), p.decide(question().copy(
            options = listOf(FlowOption("答案甲", null, null), FlowOption("答案乙", b, false)))))
    }

    @Test fun `multi select remembers verified offscreen selections`() {
        val p = AutomationPolicy(navigation = "up")
        val o = question().copy(answers = listOf("答案甲", "答案乙"))
        assertEquals(FlowAction.Tap(a, "选择答案"), p.decide(o))
        assertEquals(FlowAction.Tap(b, "选择答案"), p.decide(o.copy(options = listOf(FlowOption("答案甲", a, true), FlowOption("答案乙", b, false)))))
        assertEquals(FlowAction.Swipe(FlowSwipe.UP, true), p.decide(o.copy(options = listOf(FlowOption("答案甲", null, null), FlowOption("答案乙", b, true)))))
    }

    @Test fun `unknown selection state pauses rather than toggles`() {
        assertTrue(AutomationPolicy(navigation = "up").decide(question(selected = null)) is FlowAction.Pause)
    }

    @Test fun `failed swipe never reselects answer`() {
        val p = AutomationPolicy(navigation = "up")
        p.decide(question(selected = true))
        repeat(2) { assertEquals(FlowAction.Swipe(FlowSwipe.UP, true), p.decide(question())) }
        assertTrue(p.decide(question()) is FlowAction.Pause)
    }

    @Test fun `swipe direction is configurable`() {
        val p = AutomationPolicy(navigation = "left")
        assertEquals(FlowAction.Swipe(FlowSwipe.LEFT, true), p.decide(question(selected = true).copy(next = null)))
    }

    @Test fun `navigation ignores confirm and next buttons`() {
        val p = AutomationPolicy(navigation = "up")
        val o = question(selected = true).copy(confirm = b)
        assertEquals(FlowAction.Swipe(FlowSwipe.UP, true), p.decide(o))
        assertEquals(FlowAction.Swipe(FlowSwipe.UP, true), p.decide(o))
    }

    @Test fun `low confidence causes no gesture`() {
        assertTrue(AutomationPolicy(navigation = "up").decide(question().copy(confidence = 0.5)) is FlowAction.Pause)
    }

    @Test fun `model changing answer on same question pauses`() {
        val p = AutomationPolicy(navigation = "up")
        p.decide(question())
        assertTrue(p.decide(question().copy(answers = listOf("答案乙"))) is FlowAction.Pause)
    }

    @Test fun `submission disabled by default`() {
        assertTrue(AutomationPolicy(navigation = "up").decide(question(selected = true).copy(last = true, submit = next, unanswered = 0)) is FlowAction.Pause)
    }

    @Test fun `all visible numbered questions verified allows opening submit dialog`() {
        val p = AutomationPolicy(autoSubmit = true)
        p.decide(question(selected = true).copy(number = 1, total = 2))
        assertEquals(FlowAction.Tap(next, "提交全部答案"), p.decide(question("2", true).copy(
            number = 2, total = 2, last = true, next = null, submit = next)))
        assertEquals(FlowAction.Tap(b, "确认提交"), p.decide(FlowObservation(
            FlowPage.SUBMIT_DIALOG, 0.99, submitConfirm = b)))
    }

    @Test fun `starting midway cannot infer skipped questions answered`() {
        val p = AutomationPolicy(autoSubmit = true)
        assertTrue(p.decide(question("2", true).copy(number = 2, total = 2, last = true,
            next = null, submit = next)) is FlowAction.Pause)
    }

    @Test fun `known unanswered count overrides locally completed sequence`() {
        val p = AutomationPolicy(autoSubmit = true)
        assertTrue(p.decide(question(selected = true).copy(number = 1, total = 1,
            last = true, submit = next, unanswered = 1)) is FlowAction.Pause)
    }

    @Test fun `unknown unanswered count blocks submission`() {
        assertTrue(AutomationPolicy(autoSubmit = true).decide(question(selected = true).copy(last = true, submit = next)) is FlowAction.Pause)
    }

    @Test fun `known unanswered questions block submission`() {
        assertTrue(AutomationPolicy(autoSubmit = true).decide(question(selected = true).copy(last = true, submit = next, unanswered = 1)) is FlowAction.Pause)
    }

    @Test fun `submission confirms once and verifies success`() {
        val p = AutomationPolicy(autoSubmit = true)
        val o = question(selected = true).copy(last = true, submit = next, unanswered = 0)
        assertEquals(FlowAction.Tap(next, "提交全部答案"), p.decide(o))
        val dialog = FlowObservation(FlowPage.SUBMIT_DIALOG, 0.99, submitConfirm = b, unanswered = 0)
        assertEquals(FlowAction.Tap(b, "确认提交"), p.decide(dialog))
        assertTrue(p.decide(dialog) is FlowAction.Pause)
        assertEquals(FlowAction.Done, p.decide(FlowObservation(FlowPage.RESULT, 0.99, submissionSucceeded = true)))
    }

    @Test fun `existing dialog does not submit without completing run`() {
        assertTrue(AutomationPolicy(autoSubmit = true).decide(FlowObservation(FlowPage.SUBMIT_DIALOG, 0.99,
            submitConfirm = next, unanswered = 0)) is FlowAction.Pause)
    }

    @Test fun `result page without success is not reported as success`() {
        assertTrue(AutomationPolicy(navigation = "up").decide(FlowObservation(FlowPage.RESULT, 0.99)) is FlowAction.Pause)
    }

    @Test fun `duplicate option content is rejected`() {
        assertTrue(AutomationPolicy(navigation = "up").decide(question().copy(options = listOf(FlowOption("答案甲", a, false), FlowOption("答案甲", b, false)))) is FlowAction.Pause)
    }

    @Test fun `single answer can automatically advance on tap`() {
        val p = AutomationPolicy(navigation = "up")
        p.decide(question())
        assertEquals(FlowAction.Tap(a, "选择答案"), p.decide(question("2")))
    }

    @Test fun `unexpected question switch pauses`() {
        val p = AutomationPolicy(navigation = "up")
        p.decide(question().copy(complete = false))
        assertTrue(p.decide(question("2")) is FlowAction.Pause)
    }

    @Test fun `parser retains current coordinates and unknown counts`() {
        val o = FlowObservation.parse("""{"page_kind":"question","confidence":0.99,"question_key":"1","question":"题干","question_complete":true,"options":[{"text":"答案甲","box":[10,30,70,8],"selected":false}],"answer_texts":["答案甲"],"scroll_direction":"none","unanswered_count":null}""")
        assertEquals(a, o.options[0].box)
        assertNull(o.unanswered)
        assertEquals(FlowAction.Tap(a, "选择答案"), AutomationPolicy(navigation = "up").decide(o))
    }

    @Test(expected = IllegalArgumentException::class) fun `parser rejects out of screen coordinates`() {
        FlowObservation.parse("""{"page_kind":"question","confidence":0.99,"options":[{"text":"a","box":[90,90,20,20]}]}""")
    }

    @Test(expected = IllegalArgumentException::class) fun `fractional unanswered count cannot round down to zero`() {
        FlowObservation.parse("""{"page_kind":"submit_dialog","confidence":0.99,"unanswered_count":0.9}""")
    }
}
