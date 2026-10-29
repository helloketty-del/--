package com.futureyyy.tijing

/**
 * Maps Accessibility window ids to the application package reported for each
 * window. Vivo can omit a window root while still exposing the focused window.
 */
internal class ForegroundTargetMemory {
    private val packagesByWindowId = mutableMapOf<Int, String>()

    @Synchronized
    fun record(windowId: Int, applicationPackage: String) {
        if (windowId >= 0 && applicationPackage.isNotBlank()) {
            packagesByWindowId[windowId] = applicationPackage
        }
    }

    /**
     * Some Vivo builds expose an active window title before exposing its node
     * root. Seed the package mapping from stable titles for the supported
     * quiz targets so a session can start after the target is already open.
     */
    @Synchronized
    fun recordKnownTarget(windowId: Int, windowTitle: CharSequence?): String? {
        val applicationPackage = packageForKnownTitle(windowTitle) ?: return null
        if (windowId >= 0) packagesByWindowId[windowId] = applicationPackage
        return applicationPackage
    }

    @Synchronized
    fun matchesTargetInAnyActiveWindow(targetPackage: String, activeWindowIds: Iterable<Int>): Boolean {
        return activeWindowIds.any { windowId -> packagesByWindowId[windowId] == targetPackage }
    }

    private fun packageForKnownTitle(windowTitle: CharSequence?): String? = when (windowTitle?.toString()?.trim()) {
        "微信" -> WECHAT_PACKAGE
        "题库模拟器" -> SIMULATOR_PACKAGE
        else -> null
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val SIMULATOR_PACKAGE = "com.futureyyy.tijingsimulator"
    }
}
