package com.futureyyy.tijing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTargetMemoryTest {
    private val memory = ForegroundTargetMemory()

    @Test
    fun matchesTheTargetMappedToTheActiveWindow() {
        memory.record(windowId = 21, applicationPackage = "com.futureyyy.tijingsimulator")

        assertTrue(
            memory.matchesTargetInAnyActiveWindow(
                targetPackage = "com.futureyyy.tijingsimulator",
                activeWindowIds = listOf(21),
            ),
        )
    }

    @Test
    fun doesNotMatchWhenAnotherWindowIsActive() {
        memory.record(windowId = 21, applicationPackage = "com.futureyyy.tijingsimulator")
        memory.record(windowId = 22, applicationPackage = "com.tencent.mm")

        assertFalse(
            memory.matchesTargetInAnyActiveWindow(
                targetPackage = "com.futureyyy.tijingsimulator",
                activeWindowIds = listOf(22),
            ),
        )
    }

    @Test
    fun laterEventForTheSameWindowReplacesThePreviousPackage() {
        memory.record(windowId = 21, applicationPackage = "com.futureyyy.tijingsimulator")
        memory.record(windowId = 21, applicationPackage = "com.tencent.mm")

        assertFalse(
            memory.matchesTargetInAnyActiveWindow(
                targetPackage = "com.futureyyy.tijingsimulator",
                activeWindowIds = listOf(21),
            ),
        )
        assertTrue(
            memory.matchesTargetInAnyActiveWindow(
                targetPackage = "com.tencent.mm",
                activeWindowIds = listOf(21),
            ),
        )
    }

    @Test
    fun recordsSimulatorFromAnActiveWindowTitle() {
        assertTrue(
            memory.recordKnownTarget(windowId = 21, windowTitle = "题库模拟器") ==
                "com.futureyyy.tijingsimulator",
        )
        assertTrue(
            memory.matchesTargetInAnyActiveWindow(
                targetPackage = "com.futureyyy.tijingsimulator",
                activeWindowIds = listOf(21),
            ),
        )
    }

    @Test
    fun recordsWeChatFromAnActiveWindowTitle() {
        assertTrue(
            memory.recordKnownTarget(windowId = 22, windowTitle = "微信") == "com.tencent.mm",
        )
        assertTrue(
            memory.matchesTargetInAnyActiveWindow(
                targetPackage = "com.tencent.mm",
                activeWindowIds = listOf(22),
            ),
        )
    }

    @Test
    fun ignoresUnrelatedWindowTitles() {
        assertTrue(memory.recordKnownTarget(windowId = 23, windowTitle = "设置") == null)
        assertFalse(
            memory.matchesTargetInAnyActiveWindow(
                targetPackage = "com.futureyyy.tijingsimulator",
                activeWindowIds = listOf(23),
            ),
        )
    }
}
