package com.futureyyy.tijing

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Path
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.GestureDescription
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

class QuizAccessibilityService : AccessibilityService() {
    private data class PendingGesture(
        val description: GestureDescription,
        val targetPackage: String,
        val completion: (Boolean) -> Unit,
    )

    private val gestureHandler = Handler(Looper.getMainLooper())
    private val gestureLock = Any()
    private val pendingGestures = ArrayDeque<PendingGesture>()
    private val foregroundTargetMemory = ForegroundTargetMemory()
    private var inFlightGesture: PendingGesture? = null

    @Volatile
    private var destroyed = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        synchronized(gestureLock) { destroyed = false }
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        rememberKnownActiveWindowTargets()
        debugGesture("service-connected")
    }

    fun tapNormalized(nx: Float, ny: Float): Boolean {
        return tapNormalizedAsync(nx, ny) {}
    }

    fun tapNormalized(nx: Float, ny: Float, targetPackage: String): Boolean {
        return tapNormalizedAsync(nx, ny, targetPackage) {}
    }

    fun tapNormalizedAsync(nx: Float, ny: Float, completion: (Boolean) -> Unit): Boolean {
        return tapNormalizedAsync(nx, ny, DEFAULT_TARGET_PACKAGE, completion)
    }

    fun tapNormalizedAsync(
        nx: Float,
        ny: Float,
        targetPackage: String,
        completion: (Boolean) -> Unit,
    ): Boolean {
        val finish = singleCompletion(completion)
        debugGesture("tap-request target=$targetPackage nx=$nx ny=$ny")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            debugGesture("tap-rejected-sdk")
            return reject(finish)
        }
        if (!nx.isFinite() || !ny.isFinite() || nx !in 0f..1f || ny !in 0f..1f) {
            debugGesture("tap-rejected-coordinates")
            return reject(finish)
        }
        if (!isEligibleTargetPackage(targetPackage)) {
            debugGesture("tap-rejected-target")
            return reject(finish)
        }
        if (!isTargetForeground(targetPackage)) {
            debugGesture("tap-rejected-foreground")
            return reject(finish)
        }
        val metrics = fullDisplayMetrics()
        val x = nx * (metrics.widthPixels - 1)
        val y = ny * (metrics.heightPixels - 1)
        return enqueueTap(x, y, targetPackage, finish)
    }

    fun tapText(text: String): Boolean {
        return tapTextAsync(text) {}
    }

    fun tapText(text: String, targetPackage: String): Boolean {
        return tapTextAsync(text, targetPackage) {}
    }

    fun tapTextAsync(text: String, completion: (Boolean) -> Unit): Boolean {
        return tapTextAsync(text, DEFAULT_TARGET_PACKAGE, completion)
    }

    fun tapTextAsync(
        text: String,
        targetPackage: String,
        completion: (Boolean) -> Unit,
    ): Boolean {
        val finish = singleCompletion(completion)
        val query = text.trim()
        debugGesture("text-tap-request target=$targetPackage length=${query.length}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || query.isEmpty() || !isEligibleTargetPackage(targetPackage)) {
            debugGesture("text-tap-rejected-input")
            return reject(finish)
        }
        val root = findTargetRoot(targetPackage) ?: run {
            debugGesture("text-tap-rejected-root")
            return reject(finish)
        }
        val nodesToRecycle = ArrayList<AccessibilityNodeInfo>()
        nodesToRecycle.add(root)
        val bounds = try {
            val matches = root.findAccessibilityNodeInfosByText(query).orEmpty()
            nodesToRecycle.addAll(matches)
            val node = matches.firstOrNull { candidate ->
                candidate.isVisibleToUser && (
                    candidate.text?.toString()?.trim() == query
                        || candidate.contentDescription?.toString()?.trim() == query
                    )
            } ?: run {
                debugGesture("text-tap-rejected-not-found")
                return reject(finish)
            }
            Rect().also(node::getBoundsInScreen)
        } catch (error: Exception) {
            debugGesture("text-tap-rejected-exception=${error.javaClass.simpleName}")
            return reject(finish)
        } finally {
            recycleNodes(nodesToRecycle)
        }
        if (bounds.isEmpty) {
            debugGesture("text-tap-rejected-bounds")
            return reject(finish)
        }
        debugGesture("text-tap-bounds x=${bounds.exactCenterX()} y=${bounds.exactCenterY()}")
        return enqueueTap(bounds.exactCenterX(), bounds.exactCenterY(), targetPackage, finish)
    }

    private fun enqueueTap(
        x: Float,
        y: Float,
        targetPackage: String,
        completion: (Boolean) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            debugGesture("enqueue-rejected-sdk")
            return reject(completion)
        }
        if (!isTargetForeground(targetPackage)) {
            debugGesture("enqueue-rejected-foreground")
            return reject(completion)
        }
        debugGesture("enqueue x=$x y=$y")
        val path = Path().apply { moveTo(x, y) }
        return enqueuePath(path, 70, targetPackage, completion)
    }

    fun tapPointAsync(point: TapPoint, targetPackage: String, completion: (Boolean) -> Unit): Boolean {
        val finish = singleCompletion(completion)
        if (!isEligibleTargetPackage(targetPackage) || !isTargetForeground(targetPackage)) return reject(finish)
        val m = fullDisplayMetrics()
        val path = Path().apply { moveTo(point.x / 100f * (m.widthPixels - 1), point.y / 100f * (m.heightPixels - 1)) }
        return enqueuePath(path, point.durationMs, targetPackage, finish)
    }

    fun swipeAsync(direction: FlowSwipe, targetPackage: String, completion: (Boolean) -> Unit): Boolean {
        return swipePathAsync(SwipePath.defaultFor(direction), targetPackage, completion)
    }

    fun swipePathAsync(profile: SwipePath, targetPackage: String, completion: (Boolean) -> Unit): Boolean {
        val finish = singleCompletion(completion)
        if (!isEligibleTargetPackage(targetPackage) || !isTargetForeground(targetPackage)) return reject(finish)
        val m = fullDisplayMetrics()
        val (sx, sy, ex, ey) = profile.pixels(m.widthPixels, m.heightPixels)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        return enqueuePath(path, profile.durationMs, targetPackage, finish)
    }

    private fun enqueuePath(path: Path, duration: Long, targetPackage: String, completion: (Boolean) -> Unit): Boolean {
        val pending = PendingGesture(
            description = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
            targetPackage = targetPackage,
            completion = completion,
        )
        val accepted = synchronized(gestureLock) {
            if (destroyed) {
                false
            } else {
                pendingGestures.addLast(pending)
                true
            }
        }
        if (!accepted) {
            debugGesture("enqueue-rejected-destroyed")
            return reject(completion)
        }
        debugGesture("enqueue-accepted")
        gestureHandler.post { dispatchNextGesture() }
        return true
    }

    private fun fullDisplayMetrics(): DisplayMetrics {
        // Use the same full display basis as MediaProjection, including system bars.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    fun canPerformGestures(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    /** A click callback is only delivery evidence. The runner must verify the resulting page. */
    fun tapConfirmationAsync(box: NormalizedBox?, attempt: Int, targetPackage: String, completion: (Boolean) -> Unit) {
        ConfirmTapDispatcher.dispatch(
            tapText = { done ->
                performTextActionClickAsync("确认", targetPackage) { clicked ->
                    if (clicked) done(true) else tapTextAsync("确认", targetPackage, done)
                }
            },
            tapCoordinates = box?.let { currentBox ->
                { done ->
                    val y = currentBox.y + currentBox.height * if (attempt >= 3) 0.65f else 0.5f
                    tapNormalizedAsync(currentBox.centerX / 100f, y / 100f, targetPackage, done)
                    Unit
                }
            },
            completion = completion,
            preferCoordinates = attempt >= 2,
        )
    }

    private fun performTextActionClickAsync(text: String, targetPackage: String, completion: (Boolean) -> Unit) {
        val finish = singleCompletion(completion)
        gestureHandler.post {
            if (destroyed || !isTargetForeground(targetPackage)) { finish(false); return@post }
            val nodes = ArrayList<AccessibilityNodeInfo>()
            val clicked = try {
                val root = findTargetRoot(targetPackage)
                if (root == null) false else {
                    nodes.add(root)
                    val matches = root.findAccessibilityNodeInfosByText(text).orEmpty()
                    nodes.addAll(matches)
                    var target = matches.firstOrNull {
                        it.isVisibleToUser && it.isEnabled &&
                            (it.text?.toString()?.trim() == text || it.contentDescription?.toString()?.trim() == text)
                    }
                    var depth = 0
                    while (target != null && !target.isClickable && depth++ < 5) {
                        target = target.parent
                        target?.let(nodes::add)
                    }
                    target?.let { it.isVisibleToUser && it.isEnabled && it.isClickable &&
                        isTargetForeground(targetPackage) && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
                }
            } catch (_: Exception) { false } finally { recycleNodes(nodes) }
            finish(clicked)
        }
    }

    fun cancelGestures() {
        if (Looper.myLooper() == gestureHandler.looper) {
            cancelAllGestures()
        } else {
            gestureHandler.post { cancelAllGestures() }
        }
    }

    private fun dispatchNextGesture() {
        if (inFlightGesture != null) return
        val pending = synchronized(gestureLock) {
            if (pendingGestures.isEmpty()) null else pendingGestures.removeFirst()
        } ?: return
        inFlightGesture = pending
        if (!isTargetForeground(pending.targetPackage)) {
            debugGesture("foreground-rejected")
            cancelAllGestures()
            return
        }
        val accepted = try {
            dispatchGesture(
                pending.description,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        debugGesture("completed")
                        finishGesture(pending, true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        debugGesture("cancelled")
                        finishGesture(pending, false)
                    }
                },
                gestureHandler,
            )
        } catch (error: Exception) {
            debugGesture("dispatch-exception=${error.javaClass.simpleName}")
            false
        }
        if (!accepted) {
            debugGesture("dispatch-rejected")
            finishGesture(pending, false)
        } else {
            debugGesture("dispatched")
        }
    }

    private fun finishGesture(pending: PendingGesture, succeeded: Boolean) {
        if (inFlightGesture !== pending) return
        inFlightGesture = null
        debugGesture("finished success=$succeeded")
        // A successful confirm can rebuild the WeChat window before this callback runs.
        // The foreground checks before dispatch already guard the target application.
        pending.completion(succeeded)
        dispatchNextGesture()
    }

    private fun cancelAllGestures() {
        val cancelled = ArrayList<PendingGesture>()
        inFlightGesture?.let(cancelled::add)
        inFlightGesture = null
        synchronized(gestureLock) {
            while (pendingGestures.isNotEmpty()) cancelled.add(pendingGestures.removeFirst())
        }
        if (cancelled.isNotEmpty()) debugGesture("cancelled count=${cancelled.size}")
        cancelled.forEach { it.completion(false) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: return
        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackage != packageName &&
            eventPackage !in NON_TARGET_PACKAGES
        ) {
            foregroundTargetMemory.record(event.windowId, eventPackage)
        }
        val activeTarget = synchronized(gestureLock) {
            inFlightGesture?.targetPackage ?: pendingGestures.firstOrNull()?.targetPackage
        }
        if (eventPackage == activeTarget || eventPackage == packageName) return
        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            isFocusedApplicationWindow(event.windowId)
        ) {
            debugGesture("cancelled-by-application-window")
            cancelAllGestures()
        }
    }

    override fun onInterrupt() {
        cancelGestures()
    }

    override fun onDestroy() {
        synchronized(gestureLock) { destroyed = true }
        cancelAllGestures()
        gestureHandler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun isWeChatForeground(): Boolean {
        return isTargetForeground(DEFAULT_TARGET_PACKAGE)
    }

    fun isTargetForeground(targetPackage: String): Boolean {
        if (!isEligibleTargetPackage(targetPackage)) return false
        val root = findTargetRoot(targetPackage)
        if (root != null) {
            recycleNodes(listOf(root))
            return true
        }
        return activeWindowIds().let { activeWindowIds ->
            val matches =
            foregroundTargetMemory.matchesTargetInAnyActiveWindow(targetPackage, activeWindowIds)
            if (!matches) debugForegroundMiss(targetPackage)
            matches
        }
    }

    fun foregroundApplicationPackage(): String? {
        val root = findForegroundApplicationRoot()
        if (root != null) {
            return try {
                root.packageName?.toString()?.takeIf(::isEligibleTargetPackage)
            } finally {
                recycleNodes(listOf(root))
            }
        }
        return rememberKnownActiveWindowTargets().firstOrNull()
    }

    private fun findTargetRoot(targetPackage: String): AccessibilityNodeInfo? {
        val activeRoot = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (activeRoot?.packageName?.toString() == targetPackage) return activeRoot
        activeRoot?.let { recycleNodes(listOf(it)) }

        // Screen capture and overlays can temporarily make a system window active. In that
        // case, the focused application window still identifies the visible target safely.
        for (window in targetCandidateWindows()) {
            foregroundTargetMemory.recordKnownTarget(window.id, window.title)
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            }
            if (root?.packageName?.toString() == targetPackage) return root
            root?.let { recycleNodes(listOf(it)) }
        }
        return null
    }

    private fun findForegroundApplicationRoot(): AccessibilityNodeInfo? {
        val activeRoot = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (activeRoot != null && isEligibleTargetPackage(activeRoot.packageName?.toString().orEmpty())) {
            return activeRoot
        }
        activeRoot?.let { recycleNodes(listOf(it)) }

        for (window in targetCandidateWindows()) {
            foregroundTargetMemory.recordKnownTarget(window.id, window.title)
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            }
            if (root != null && isEligibleTargetPackage(root.packageName?.toString().orEmpty())) {
                return root
            }
            root?.let { recycleNodes(listOf(it)) }
        }
        return null
    }

    private fun activeWindowIds(): List<Int> {
        val candidateWindows = targetCandidateWindows()
        candidateWindows.forEach { window -> foregroundTargetMemory.recordKnownTarget(window.id, window.title) }
        return candidateWindows.map { window -> window.id }
    }

    private fun rememberKnownActiveWindowTargets(): List<String> = targetCandidateWindows()
        .mapNotNull { window -> foregroundTargetMemory.recordKnownTarget(window.id, window.title) }

    private fun targetCandidateWindows() = allWindows().filter { window ->
        ForegroundWindowPolicy.includeInTargetCheck(
            isActive = window.isActive,
            isFocused = window.isFocused,
            isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
        )
    }

    private fun isFocusedApplicationWindow(windowId: Int): Boolean = allWindows().any { window ->
        window.id == windowId && ForegroundWindowPolicy.cancelsPendingGesture(
            isActive = window.isActive,
            isFocused = window.isFocused,
            isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
        )
    }

    private fun allWindows() = try {
        windows.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun debugForegroundMiss(targetPackage: String) {
        if (!BuildConfig.DEBUG) return
        val summary = allWindows().joinToString(separator = ";") { window ->
            "id=${window.id},type=${window.type},active=${window.isActive},focused=${window.isFocused},titleLength=${window.title?.length ?: 0}"
        }
        Log.i(LOG_TAG, "foreground-miss target=$targetPackage windows=[$summary]")
    }

    private fun debugGesture(event: String) {
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, "gesture-$event")
    }

    private fun isEligibleTargetPackage(targetPackage: String): Boolean =
        targetPackage in KNOWN_QUIZ_TARGET_PACKAGES || (
            targetPackage.isNotBlank() &&
                targetPackage != packageName &&
                targetPackage !in NON_TARGET_PACKAGES
            )

    @Suppress("DEPRECATION")
    private fun recycleNodes(nodes: Iterable<AccessibilityNodeInfo>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val recycled = Collections.newSetFromMap(
            IdentityHashMap<AccessibilityNodeInfo, Boolean>(),
        )
        nodes.forEach { node ->
            if (recycled.add(node)) runCatching { node.recycle() }
        }
    }

    private fun singleCompletion(completion: (Boolean) -> Unit): (Boolean) -> Unit {
        val completed = AtomicBoolean(false)
        return { succeeded ->
            if (completed.compareAndSet(false, true)) runCatching { completion(succeeded) }
        }
    }

    private fun reject(completion: (Boolean) -> Unit): Boolean {
        completion(false)
        return false
    }

    companion object {
        private const val LOG_TAG = "TiJingA11y"
        private const val DEFAULT_TARGET_PACKAGE = "com.tencent.mm"
        private const val SIMULATOR_TARGET_PACKAGE = "com.futureyyy.tijingsimulator"
        private val KNOWN_QUIZ_TARGET_PACKAGES = setOf(DEFAULT_TARGET_PACKAGE, SIMULATOR_TARGET_PACKAGE)
        private val NON_TARGET_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        @Volatile
        var instance: QuizAccessibilityService? = null
    }
}
