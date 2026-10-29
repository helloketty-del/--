package com.futureyyy.tijing

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prefers an exact, visible accessibility label for the confirmation button.
 * If the target app does not expose that label, the vision-model coordinate is
 * still used as the fallback.
 */
internal object ConfirmTapDispatcher {
    fun dispatch(
        tapText: ((Boolean) -> Unit) -> Unit,
        tapCoordinates: (((Boolean) -> Unit) -> Unit)?,
        completion: (Boolean) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        val coordinateAttempted = AtomicBoolean(false)

        fun finish(succeeded: Boolean) {
            if (completed.compareAndSet(false, true)) completion(succeeded)
        }

        fun fallbackToCoordinates() {
            if (!coordinateAttempted.compareAndSet(false, true)) return
            val fallback = tapCoordinates ?: run {
                finish(false)
                return
            }
            try {
                fallback(::finish)
            } catch (_: Exception) {
                finish(false)
            }
        }

        try {
            tapText { succeeded ->
                if (succeeded) finish(true) else fallbackToCoordinates()
            }
        } catch (_: Exception) {
            fallbackToCoordinates()
        }
    }
}
