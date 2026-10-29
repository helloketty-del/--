package com.futureyyy.tijing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWindowPolicyTest {
    @Test
    fun keepsVisibleApplicationInTheForegroundCheckDuringSystemOverlay() {
        assertTrue(
            ForegroundWindowPolicy.includeInTargetCheck(
                isActive = false,
                isFocused = false,
                isApplicationWindow = true,
            ),
        )
    }

    @Test
    fun doesNotCancelForAForegroundSystemOverlay() {
        assertFalse(
            ForegroundWindowPolicy.cancelsPendingGesture(
                isActive = true,
                isFocused = true,
                isApplicationWindow = false,
            ),
        )
    }

    @Test
    fun cancelsWhenAnotherApplicationActuallyReceivesFocus() {
        assertTrue(
            ForegroundWindowPolicy.cancelsPendingGesture(
                isActive = true,
                isFocused = false,
                isApplicationWindow = true,
            ),
        )
    }
}
