package com.futureyyy.tijing

/** Rules for distinguishing a real app switch from a transient system overlay. */
internal object ForegroundWindowPolicy {
    fun includeInTargetCheck(
        isActive: Boolean,
        isFocused: Boolean,
        isApplicationWindow: Boolean,
    ): Boolean = isActive || isFocused || isApplicationWindow

    fun cancelsPendingGesture(
        isActive: Boolean,
        isFocused: Boolean,
        isApplicationWindow: Boolean,
    ): Boolean = isApplicationWindow && (isActive || isFocused)
}
