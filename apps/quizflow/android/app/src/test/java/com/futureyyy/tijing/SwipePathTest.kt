package com.futureyyy.tijing

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SwipePathTest {
    private fun config(extra: String = "") = QuizRuntimeConfig.fromJson("""{"endpoint":"https://example.test/v1","model":"vision","apiKey":"test"$extra}""")

    @Test fun `old configuration preserves confirmation mode`() {
        assertEquals("confirm", config().navigation)
        assertEquals("confirm", config(""", "navigation":"auto"""").navigation)
        assertEquals("up", config(""", "navigation":"down"""").navigation)
    }

    @Test fun `custom paths are passed through runtime configuration`() {
        val c = config(""", "navigation":"left", "swipeProfiles":{"left":{"startX":91,"startY":62,"endX":9,"endY":62,"durationMs":900}}""")
        assertEquals(SwipePath(91f, 62f, 9f, 62f, 900), c.swipeProfiles[FlowSwipe.LEFT])
        assertEquals(SwipePath.defaultFor(FlowSwipe.UP), c.swipeProfiles[FlowSwipe.UP])
    }

    @Test fun `percentages scale to full display and stay inside pixel bounds`() {
        val p = SwipePath(0f, 100f, 100f, 0f, 500)
        assertEquals(listOf(0f, 2399f, 1079f, 0f), p.pixels(1080, 2400))
        assertEquals(listOf(0f, 1079f, 2399f, 0f), p.pixels(2400, 1080))
    }

    @Test fun `default profiles follow the selected finger direction`() {
        for ((direction, path) in SwipePath.defaults()) assertEquals(path, path.requireDirection(direction))
    }

    @Test(expected = IllegalArgumentException::class) fun `reject backwards up gesture`() {
        SwipePath(50f, 20f, 50f, 80f).requireDirection(FlowSwipe.UP)
    }

    @Test(expected = IllegalArgumentException::class) fun `reject coordinates beyond display`() { SwipePath(-1f, 80f, 50f, 20f) }
    @Test(expected = IllegalArgumentException::class) fun `reject zero length swipe`() { SwipePath(50f, 50f, 50f, 50f) }
    @Test(expected = IllegalArgumentException::class) fun `reject invalid duration`() { SwipePath(50f, 80f, 50f, 20f, 0) }
    @Test(expected = IllegalArgumentException::class) fun `reject nonfinite coordinate`() { SwipePath(Float.NaN, 80f, 50f, 20f) }
    @Test(expected = IllegalArgumentException::class) fun `reject fractional native duration`() {
        SwipePath.parseProfiles(JsonParser.parseString("""{"up":{"durationMs":500.5}}"""))
    }
}
