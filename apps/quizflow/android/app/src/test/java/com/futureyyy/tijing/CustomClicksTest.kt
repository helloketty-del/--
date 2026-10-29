package com.futureyyy.tijing

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CustomClicksTest {
    private val first = TapPoint(50f, 94f, 120)
    private val next = TapPoint(88f, 94f, 90)
    private fun question(key: String = "1") = FlowObservation(FlowPage.QUESTION, 0.99, key, "题干", true,
        listOf(FlowOption("答案", NormalizedBox(10f, 30f, 80f, 8f), true)), listOf("答案"))

    @Test fun `custom next point is used without model navigation boxes`() {
        val p = AutomationPolicy(navigation = "tap", customClicks = CustomClicks(next = next))
        assertEquals(FlowAction.CustomTap(next, "自定义下一题"), p.decide(question()))
    }

    @Test fun `two custom points execute once in order`() {
        val p = AutomationPolicy(navigation = "tap", customClicks = CustomClicks(next, first, true))
        assertEquals(FlowAction.CustomTap(first, "自定义提交本题"), p.decide(question()))
        assertEquals(FlowAction.CustomTap(next, "自定义下一题"), p.decide(question()))
        assertEquals(FlowAction.CustomTap(next, "自定义下一题"), p.decide(question()))
    }

    @Test fun `new question resets the first custom click`() {
        val p = AutomationPolicy(navigation = "tap", customClicks = CustomClicks(next, first, true))
        p.decide(question()); p.decide(question())
        assertEquals(FlowAction.CustomTap(first, "自定义提交本题"), p.decide(question("2")))
    }

    @Test fun `last question does not use next point to bypass final submission setting`() {
        val p = AutomationPolicy(navigation = "tap", customClicks = CustomClicks(next, first, true))
        val last = question().copy(last = true, unanswered = 0)
        assertEquals(FlowAction.CustomTap(first, "自定义提交本题"), p.decide(last))
        assertTrue(p.decide(last) is FlowAction.Pause)
    }

    @Test fun `native runtime retains coordinates and click duration`() {
        val config = QuizRuntimeConfig.fromJson("""{"endpoint":"https://example.test/v1","model":"vision","apiKey":"test","navigation":"tap","customClicks":{"next":{"x":88,"y":94,"durationMs":90},"confirm":{"x":50,"y":94,"durationMs":120},"confirmFirst":true}}""")
        assertEquals(CustomClicks(next, first, true), config.customClicks)
        assertEquals(NavigationCommand.Click(next), NavigationCommand.from(config))
    }

    @Test fun `action test requires no API key but normal operation does`() {
        val json = """{"endpoint":"https://example.test/v1","model":"vision","mode":"auto","navigation":"left","gestureTestOnly":true}"""
        val config = QuizRuntimeConfig.fromJson(json)
        assertTrue(config.gestureTestOnly)
        assertEquals("", config.apiKey)
        assertEquals(NavigationCommand.Swipe(SwipePath.defaultFor(FlowSwipe.LEFT)), NavigationCommand.from(config))
        assertThrows(IllegalArgumentException::class.java) { QuizRuntimeConfig.fromJson(json.replace("true", "false")) }
    }

    @Test fun `invalid custom coordinate or long press duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TapPoint(101f, 50f) }
        assertThrows(IllegalArgumentException::class.java) { TapPoint(50f, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TapPoint(50f, 50f, 1000) }
        assertThrows(IllegalArgumentException::class.java) {
            CustomClicks.parse(JsonParser.parseString("""{"next":{"durationMs":99.5}}"""))
        }
    }

    @Test fun `custom navigation ignores unused invalid model next box`() {
        val json = """{"page_kind":"question","confidence":0.99,"question_key":"1","question":"题干","question_complete":true,"options":[{"text":"答案","box":[10,30,80,8],"selected":true}],"answer_texts":["答案"],"next_box":[500,2000,50,50]}"""
        val observation = FlowObservation.parse(json, ignoreNavigationBoxes = true)
        assertEquals(FlowAction.CustomTap(next, "自定义下一题"),
            AutomationPolicy(navigation = "tap", customClicks = CustomClicks(next)).decide(observation))
        assertThrows(CoordinateResponseException::class.java) { FlowObservation.parse(json) }
    }
}
