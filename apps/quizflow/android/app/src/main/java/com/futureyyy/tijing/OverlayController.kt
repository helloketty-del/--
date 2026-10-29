package com.futureyyy.tijing

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayController(
    context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPrimaryAction()
        fun onRetry()
        fun onPause()
        fun onResume()
        fun onStop()
    }

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = appContext.resources.displayMetrics.density
    private val bubbleSize = dp(BUBBLE_SIZE_DP)
    private val markerSize = dp(MARKER_SIZE_DP)
    private val edgeMargin = dp(EDGE_MARGIN_DP)
    private val touchSlop = dp(TOUCH_SLOP_DP)

    private var bubbleView: View? = null
    private var bubbleLabel: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var cardView: LinearLayout? = null
    private var cardTitle: TextView? = null
    private var cardBody: TextView? = null
    private var cardStatus: TextView? = null
    private var cardActions: LinearLayout? = null
    private var latestSnapshot: RunnerSnapshot? = null
    private var hiddenForCapture = false
    private var markerWasVisibleForCapture = false
    private var overviewWasVisibleForCapture = false
    private var showingError = false
    private var destroyed = false
    private var autoCollapseAtMs = 0L
    private var pendingAfterCollapse: (() -> Unit)? = null

    private var captureMode = false
    private var sweepRunning = false
    private var markerView: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var answerButtonView: View? = null
    private var answerButtonParams: WindowManager.LayoutParams? = null
    private var overviewView: LinearLayout? = null
    private var overviewParams: WindowManager.LayoutParams? = null
    private var overviewScroll: ScrollView? = null
    private var overviewBar: LinearLayout? = null
    private var overviewBarParams: WindowManager.LayoutParams? = null
    private var overviewStrip: LinearLayout? = null
    private var overviewStripParams: WindowManager.LayoutParams? = null
    private var overviewX = 0
    private var overviewY = 0
    private var overviewPanelHeight = 0
    private var lastAnswers: List<SweptAnswer> = emptyList()
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun show(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || destroyed) return false
        if (!ensureViews()) return false
        bubbleView?.visibility = View.VISIBLE
        return true
    }

    fun update(snapshot: RunnerSnapshot) = onMain {
        // Capture mode is answer-only: the status card would just cover the quiz page.
        if (captureMode) return@onMain
        latestSnapshot = snapshot
        ensureViews()
        bubbleLabel?.text = labelFor(snapshot)
        bubbleView?.contentDescription = descriptionFor(snapshot)
        if (snapshot.phase == RunnerPhase.CAPTURING || snapshot.phase == RunnerPhase.RECOGNIZING) {
            showingError = false
            cardActions?.visibility = View.GONE
            cardTitle?.text = "第 ${snapshot.questionNo} 题 · ${if (snapshot.phase == RunnerPhase.CAPTURING) "准备识别" else "识别中"}"
            cardBody?.text = snapshot.message ?: "正在处理"
            cardStatus?.text = buildString {
                append("耗时 ").append(snapshot.elapsedMs).append("ms")
                append(" · 尝试 ").append(snapshot.attemptCount).append("/").append(snapshot.maxAttempts)
                snapshot.matchLabel?.let { append(" · ").append(it) }
            }
            showCard()
        }
        if (snapshot.phase == RunnerPhase.PAUSED_ERROR && !snapshot.errorMessage.isNullOrBlank()) {
            showErrorOnMain(snapshot.errorMessage)
        }
    }

    fun showAnswer(result: QuizModelResult, autoCollapse: Boolean) = onMain {
        if (captureMode) return@onMain
        ensureViews()
        showingError = false
        cardActions?.visibility = View.GONE
        val questionNo = result.questionNo ?: latestSnapshot?.questionNo ?: 1
        cardTitle?.text = "第 ${questionNo} 题 · ${typeLabel(result.type)}"
        val answers = result.answerIndices.mapNotNull { index ->
            result.options.firstOrNull { option -> option.index == index }
        }
        val letters = answers.joinToString("、") { option -> answerLabel(option.index) }
        val texts = answers.map { option -> option.text.trim() }.filter { text -> text.isNotEmpty() }
        cardBody?.text = buildString {
            append(if (result.answerText.isNullOrBlank()) "答案：" else "填空答案：")
            append(letters.ifBlank { result.answerText?.takeIf { it.isNotBlank() } ?: "--" })
            if (texts.isNotEmpty()) {
                append("\n")
                append(texts.joinToString("；"))
            }
            result.matchLabel?.let { append("\n匹配：").append(it) }
            result.matchSource?.let { append("\n来源：").append(it) }
            result.answerSource?.let { append("\n答案来源：").append(if (it == "question_bank") "题库" else "多模态视觉") }
        }
        cardStatus?.text = if (result.matchLabel != null) {
            result.matchLabel + " · 耗时 " + (latestSnapshot?.elapsedMs ?: 0L) + "ms"
        } else {
            "置信度 ${(result.confidence * 100).roundToInt().coerceIn(0, 100)}% · 耗时 " +
                (latestSnapshot?.elapsedMs ?: 0L) + "ms"
        }
        showCard()
        cardView?.setOnClickListener { collapseCardOnMain() }
        mainHandler.removeCallbacks(autoCollapseRunnable)
        autoCollapseAtMs = if (autoCollapse) SystemClock.uptimeMillis() + AUTO_COLLAPSE_MS else 0L
        if (autoCollapse) mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
    }

    fun showRecognitionStatus(message: String, matchLabel: String? = null) = onMain {
        if (captureMode) return@onMain
        ensureViews()
        showingError = false
        cardActions?.visibility = View.GONE
        val questionNo = latestSnapshot?.questionNo ?: 1
        cardTitle?.text = "第 ${questionNo} 题 · 识别中"
        cardBody?.text = message
        cardStatus?.text = buildString {
            latestSnapshot?.let { snapshot ->
                append("耗时 ").append(snapshot.elapsedMs).append("ms")
                append(" · 尝试 ").append(snapshot.attemptCount).append("/").append(snapshot.maxAttempts)
            }
            matchLabel?.let { append(" · ").append(it) }
        }
        showCard()
    }

    fun showError(message: String) = onMain {
        if (captureMode) return@onMain
        showErrorOnMain(message)
    }

    fun hideForCapture(afterHidden: () -> Unit) = onMain {
        ensureViews()
        mainHandler.removeCallbacks(captureReadyRunnable)
        captureCompletion = afterHidden
        hiddenForCapture = true
        bubbleView?.visibility = View.INVISIBLE
        cardView?.visibility = View.INVISIBLE
        // Every window we own must leave the frame, otherwise the model reads our own UI text.
        markerWasVisibleForCapture = markerView?.visibility == View.VISIBLE
        markerView?.visibility = View.INVISIBLE
        answerButtonView?.visibility = View.INVISIBLE
        overviewWasVisibleForCapture = overviewView?.visibility == View.VISIBLE
        overviewView?.visibility = View.INVISIBLE
        overviewBar?.visibility = View.INVISIBLE
        overviewStrip?.visibility = View.INVISIBLE
        mainHandler.postDelayed(captureReadyRunnable, CAPTURE_HIDE_DELAY_MS)
    }

    fun restoreAfterCapture() = onMain {
        if (!hiddenForCapture) return@onMain
        hiddenForCapture = false
        bubbleView?.visibility = View.VISIBLE
        cardView?.visibility = View.GONE
        if (markerWasVisibleForCapture) markerView?.visibility = View.VISIBLE
        answerButtonView?.visibility = View.VISIBLE
        if (overviewWasVisibleForCapture) {
            overviewView?.visibility = View.VISIBLE
            overviewBar?.visibility = View.VISIBLE
            overviewStrip?.visibility = View.VISIBLE
        }
    }

    fun runAfterAnswerCollapsed(action: () -> Unit) = onMain {
        val delayMs = (autoCollapseAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        if (cardView?.visibility != View.VISIBLE || showingError || delayMs == 0L) {
            collapseCardOnMain()
            action()
        } else {
            mainHandler.removeCallbacks(afterCollapseRunnable)
            pendingAfterCollapse = action
            mainHandler.postDelayed(afterCollapseRunnable, delayMs)
        }
    }

    fun cancelPendingGestureAction() = onMain {
        mainHandler.removeCallbacks(afterCollapseRunnable)
        val action = pendingAfterCollapse
        pendingAfterCollapse = null
        action?.invoke()
    }

    fun destroy() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyOnMain()
        } else {
            mainHandler.post { destroyOnMain() }
        }
    }

    private var captureCompletion: (() -> Unit)? = null

    private val captureReadyRunnable = Runnable {
        val completion = captureCompletion
        captureCompletion = null
        if (!destroyed && hiddenForCapture) runCatching { completion?.invoke() }
    }

    private val autoCollapseRunnable = Runnable {
        if (!destroyed && !showingError) collapseCardOnMain()
    }

    private val afterCollapseRunnable = Runnable {
        val action = pendingAfterCollapse
        pendingAfterCollapse = null
        if (!destroyed) {
            collapseCardOnMain()
            action?.invoke()
        }
    }

    private fun ensureViews(): Boolean {
        if (destroyed) return false
        if (bubbleView == null) createBubble()
        if (cardView == null) createCard()
        return bubbleView != null && cardView != null
    }

    private fun createBubble() {
        val label = TextView(appContext).apply {
            gravity = Gravity.CENTER
            setTextColor(COLOR_LIGHT)
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
            includeFontPadding = false
            setPadding(dp(3), dp(34), dp(3), dp(3))
        }
        val icon = ImageView(appContext).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
        }
        val root = FrameLayout(appContext).apply {
            background = roundedBackground(COLOR_DEEP_GREEN, BUBBLE_SIZE_DP / 2f)
            elevation = dp(8).toFloat()
            addView(
                icon,
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = dp(1)
                },
            )
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnTouchListener(BubbleTouchListener())
        }
        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - bubbleSize - edgeMargin
            y = (screenHeight() - bubbleSize) / 2
        }
        try {
            windowManager.addView(root, params)
            bubbleView = root
            bubbleLabel = label
            bubbleParams = params
        } catch (_: Exception) {
            bubbleView = null
            bubbleLabel = null
            bubbleParams = null
        }
    }

    private fun createCard() {
        val title = styledText(sizeSp = 15f, color = COLOR_LIGHT, bold = true)
        val body = styledText(sizeSp = 16f, color = COLOR_ACCENT, bold = true).apply {
            setPadding(0, dp(7), 0, 0)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val status = styledText(sizeSp = 12f, color = COLOR_MUTED).apply {
            setPadding(0, dp(7), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val actions = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
            addView(actionButton("重试") { runCatching { callbacks.onRetry() } })
            addView(actionButton("继续") { runCatching { callbacks.onResume() } })
            addView(actionButton("停止", destructive = true) { runCatching { callbacks.onStop() } })
        }
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_DEEP_GREEN, CARD_CORNER_DP)
            elevation = dp(10).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(body, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(actions, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            (screenWidth() - dp(24)).coerceAtLeast(dp(240)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(8)
        }
        try {
            windowManager.addView(root, params)
            cardView = root
            cardTitle = title
            cardBody = body
            cardStatus = status
            cardActions = actions
        } catch (_: Exception) {
            cardView = null
            cardTitle = null
            cardBody = null
            cardStatus = null
            cardActions = null
        }
    }

    private fun showErrorOnMain(message: String) {
        ensureViews()
        mainHandler.removeCallbacks(autoCollapseRunnable)
        autoCollapseAtMs = 0L
        showingError = true
        cardTitle?.text = "识别暂停"
        cardBody?.text = message.take(MAX_ERROR_LENGTH)
        cardStatus?.text = "可重试当前题，或停止本次运行"
        cardActions?.visibility = View.VISIBLE
        cardView?.setOnClickListener(null)
        showCard()
    }

    private fun showCard() {
        if (!hiddenForCapture) cardView?.visibility = View.VISIBLE
    }

    private fun collapseCardOnMain() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        autoCollapseAtMs = 0L
        if (!hiddenForCapture) cardView?.visibility = View.GONE
    }

    private fun destroyOnMain() {
        if (destroyed) return
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        captureCompletion = null
        pendingAfterCollapse = null
        listOfNotNull(cardView, bubbleView, markerView, answerButtonView, overviewView, overviewBar, overviewStrip).forEach { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
                // A failed add or a concurrent system removal leaves no window to clean up.
            }
        }
        cardView = null
        bubbleView = null
        bubbleLabel = null
        bubbleParams = null
        cardTitle = null
        cardBody = null
        cardStatus = null
        cardActions = null
        markerView = null
        markerParams = null
        answerButtonView = null
        answerButtonParams = null
        overviewView = null
        overviewParams = null
        overviewBar = null
        overviewBarParams = null
        overviewStrip = null
        overviewStripParams = null
        overviewPanelHeight = 0
        lastAnswers = emptyList()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!destroyed) action()
        } else {
            mainHandler.post { if (!destroyed) action() }
        }
    }

    private fun labelFor(snapshot: RunnerSnapshot): String = when (snapshot.phase) {
        RunnerPhase.READY -> "开始"
        RunnerPhase.CAPTURING, RunnerPhase.RECOGNIZING -> "识别中"
        RunnerPhase.SHOWING_ANSWER,
        RunnerPhase.EXECUTING,
        RunnerPhase.WAITING_PAGE_CHANGE,
        -> if (snapshot.mode == QuizMode.AUTO) "自动\n${snapshot.questionNo}" else "开始"
        RunnerPhase.PAUSED_ERROR -> "已暂停"
        RunnerPhase.STOPPED -> "完成"
    }

    private fun descriptionFor(snapshot: RunnerSnapshot): String = when (snapshot.phase) {
        RunnerPhase.READY -> "开始识别"
        RunnerPhase.CAPTURING, RunnerPhase.RECOGNIZING -> "正在识别第 ${snapshot.questionNo} 题"
        RunnerPhase.PAUSED_ERROR -> "识别已暂停"
        RunnerPhase.STOPPED -> "识别完成"
        else -> "自动识别第 ${snapshot.questionNo} 题"
    }

    private fun typeLabel(type: QuestionType): String = when (type) {
        QuestionType.SINGLE -> "单选题"
        QuestionType.MULTIPLE -> "多选题"
        QuestionType.INDEFINITE -> "不定项选择题"
        QuestionType.TRUE_FALSE -> "判断题"
        QuestionType.FILL_BLANK -> "填空题"
    }

    private fun answerLabel(index: Int): String =
        if (index in 0..25) ('A'.code + index).toChar().toString() else "#${index + 1}"

    private fun styledText(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(appContext).apply {
        setTextColor(color)
        textSize = sizeSp
        includeFontPadding = false
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun actionButton(label: String, destructive: Boolean = false, action: () -> Unit) =
        Button(appContext).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(if (destructive) COLOR_ORANGE else COLOR_DEEP_GREEN)
            background = roundedBackground(if (destructive) COLOR_LIGHT else COLOR_ACCENT, 6f)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                marginStart = dp(6)
            }
        }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * density
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun screenWidth(): Int = appContext.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = appContext.resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int {
        val resourceId = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) appContext.resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx.roundToInt()).coerceIn(edgeMargin, screenWidth() - bubbleSize - edgeMargin)
                        params.y = (startY + dy.roundToInt()).coerceIn(edgeMargin, screenHeight() - bubbleSize - edgeMargin)
                        updateBubbleLayout(view, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        params.x = screenWidth() - bubbleSize - edgeMargin
                        updateBubbleLayout(view, params)
                    } else if (event.eventTime - event.downTime >= LONG_PRESS_MS) {
                        if (canPause(latestSnapshot)) runCatching { callbacks.onPause() }
                    } else {
                        view.performClick()
                        handleBubbleClick()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun updateBubbleLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // The service may be tearing the overlay down at the same time.
        }
    }

    private fun handleBubbleClick() {
        if (captureMode) {
            if (sweepRunning) runCatching { callbacks.onStop() } else runCatching { callbacks.onPrimaryAction() }
            return
        }
        when (latestSnapshot?.phase ?: RunnerPhase.READY) {
            RunnerPhase.READY -> runCatching { callbacks.onPrimaryAction() }
            RunnerPhase.PAUSED_ERROR -> if (cardView?.visibility == View.VISIBLE) {
                collapseCardOnMain()
            } else {
                cardView?.visibility = View.VISIBLE
            }
                RunnerPhase.STOPPED -> Unit
            else -> if (cardView?.visibility == View.VISIBLE) collapseCardOnMain() else cardView?.visibility = View.VISIBLE
        }
    }

    private fun canPause(snapshot: RunnerSnapshot?): Boolean {
        if (snapshot?.mode != QuizMode.AUTO) return false
        return when (snapshot.phase) {
            RunnerPhase.CAPTURING,
            RunnerPhase.RECOGNIZING,
            RunnerPhase.SHOWING_ANSWER,
            RunnerPhase.EXECUTING,
            RunnerPhase.WAITING_PAGE_CHANGE,
            -> true
            else -> false
        }
    }

    fun setCaptureMode(enabled: Boolean) = onMain {
        captureMode = enabled
        if (enabled) {
            ensureViews()
            cardView?.visibility = View.GONE
            if (markerView == null) createMarker()
            if (answerButtonView == null) createAnswerButton()
        } else {
            removeMarker()
            removeAnswerButton()
            hideOverviewOnMain()
            lastAnswers = emptyList()
            sweepRunning = false
        }
    }

    fun updateSweepState(running: Boolean, recognized: Int = 0, pending: Int = 0) = onMain {
        sweepRunning = running
        if (captureMode) {
            val captured = recognized + pending
            bubbleLabel?.text = if (running) "停止\n$recognized/$captured" else "开始"
            bubbleView?.contentDescription = if (running) {
                "停止采集，已识别 $recognized 题，共采集 $captured 题"
            } else {
                "开始采集"
            }
            markerView?.alpha = if (running) 0.9f else 0.35f
        }
    }

    fun markerNormalized(): Pair<Float, Float>? {
        val params = markerParams ?: return null
        val width = screenWidth().coerceAtLeast(1)
        val height = screenHeight().coerceAtLeast(1)
        val nx = (params.x + markerSize / 2f) / width
        val ny = (params.y + markerSize / 2f) / height
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    /** Hides the marker so a dispatched tap lands on the quiz page underneath, then restores it. */
    fun tapMarker(action: () -> Unit) = onMain {
        val marker = markerView
        if (marker == null) {
            runCatching { action() }
            return@onMain
        }
        marker.visibility = View.INVISIBLE
        mainHandler.postDelayed({ if (!destroyed) runCatching { action() } }, MARKER_HIDE_DELAY_MS)
        mainHandler.postDelayed({
            if (!destroyed && !hiddenForCapture) marker.visibility = View.VISIBLE
        }, MARKER_RESTORE_DELAY_MS)
    }

    fun showOverview(answers: List<SweptAnswer>) = onMain {
        lastAnswers = answers
        showOverviewOnMain(answers)
    }

    fun hideOverview() = onMain { hideOverviewOnMain() }

    /** The "答" button: show the sheet again, or bring it back if it was dragged away. */
    fun toggleOverview() = onMain { resetOverviewOnMain() }

    private fun showOverviewOnMain(answers: List<SweptAnswer>) {
        if (answers.isEmpty()) return
        if (destroyed) return
        ensureViews()
        hideOverviewOnMain()
        val failed = answers.count { answer -> answer.error != null }
        val title = styledText(sizeSp = 13f, color = COLOR_PANEL_INK, bold = true).apply {
            text = if (failed > 0) {
                "识别完成 · 共 ${answers.size} 题 · 失败 $failed"
            } else {
                "识别完成 · 共 ${answers.size} 题"
            }
            setPadding(dp(4), 0, 0, 0)
        }
        val list = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            answers.sortedBy { answer -> answer.questionNo }.forEach { answer -> addView(overviewRow(answer)) }
        }
        val scroll = ScrollView(appContext).apply {
            addView(list, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // Content window: translucent white and NOT touchable, so taps fall through to the quiz page.
        val content = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            // Fully transparent: never tints or covers the page underneath.
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Control bar: the only touchable surface (drag to move, ⇕ to resize, 关闭 to dismiss).
        val resizeHandle = styledText(sizeSp = 15f, color = COLOR_PANEL_INK, bold = true).apply {
            text = "⇕"
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setOnTouchListener(ResizeWindowListener())
        }
        val close = Button(appContext).apply {
            text = "关闭"
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(30)
            minimumHeight = dp(30)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(COLOR_LIGHT)
            background = roundedBackground(COLOR_PANEL_ACTION, 6f)
            setOnClickListener { hideOverviewOnMain() }
        }
        val bar = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_PANEL_BAR_BG, CARD_CORNER_DP)
            setPadding(dp(10), dp(4), dp(8), dp(4))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(resizeHandle, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            addView(close, LinearLayout.LayoutParams.WRAP_CONTENT, dp(30))
        }
        title.setOnTouchListener(TranslateWindowListener())
        overviewScroll = scroll
        val width = (screenWidth() - dp(24)).coerceAtLeast(dp(240))
        // Content window: permanently untouchable, so taps always reach the quiz page.
        val contentParams = WindowManager.LayoutParams(
            width,
            dp(240),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        // Scroll strip: the one touchable surface over the list; drag it to scroll the answers.
        val stripGrip = styledText(sizeSp = 13f, color = COLOR_PANEL_INK, bold = true).apply {
            text = "⋮\n⋮\n⋮"
            gravity = Gravity.CENTER
            alpha = 0.75f
        }
        val strip = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBackground(COLOR_PANEL_BAR_BG, 8f)
            addView(stripGrip, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnTouchListener(ScrollStripListener())
        }
        val stripParams = WindowManager.LayoutParams(
            dp(OVERVIEW_STRIP_WIDTH_DP),
            dp(240),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        val barParams = WindowManager.LayoutParams(
            width,
            dp(OVERVIEW_BAR_HEIGHT_DP),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        try {
            windowManager.addView(content, contentParams)
            windowManager.addView(strip, stripParams)
            windowManager.addView(bar, barParams)
            overviewView = content
            overviewParams = contentParams
            overviewStrip = strip
            overviewStripParams = stripParams
            overviewBar = bar
            overviewBarParams = barParams
            overviewX = dp(12)
            overviewY = dp(12)
            overviewPanelHeight = (screenHeight() * OVERVIEW_DEFAULT_HEIGHT_PERCENT / 100).coerceAtLeast(minPanelHeight())
            applyOverviewGeometry()
        } catch (_: Exception) {
            hideOverviewOnMain()
        }
    }

    /** Dragging the right-edge strip scrolls the answer list; taps are not consumed by it. */
    private inner class ScrollStripListener : View.OnTouchListener {
        private var lastY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = lastY - event.rawY
                    if (dy != 0f) {
                        overviewScroll?.scrollBy(0, dy.roundToInt())
                        lastY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun minPanelHeight(): Int = dp(OVERVIEW_MIN_HEIGHT_DP) + dp(OVERVIEW_BAR_HEIGHT_DP)

    private fun barTextButton(label: String, action: () -> Unit) = Button(appContext).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(30)
        minimumHeight = dp(30)
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(COLOR_PANEL_INK)
        background = roundedBackground(Color.argb(255, 232, 236, 230), 6f)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(30),
        ).apply { marginStart = dp(6) }
    }

    /** Pushes the current x/y/height onto both overview windows. */
    private fun applyOverviewGeometry() {
        val content = overviewView ?: return
        val bar = overviewBar ?: return
        val contentParams = overviewParams ?: return
        val barParams = overviewBarParams ?: return
        val barHeight = dp(OVERVIEW_BAR_HEIGHT_DP)
        val contentHeight = (overviewPanelHeight - barHeight).coerceAtLeast(dp(OVERVIEW_MIN_HEIGHT_DP))
        val width = (screenWidth() - dp(24)).coerceAtLeast(dp(240))
        contentParams.gravity = Gravity.BOTTOM or Gravity.START
        contentParams.width = width
        contentParams.height = contentHeight
        contentParams.x = overviewX
        contentParams.y = overviewY
        barParams.gravity = Gravity.BOTTOM or Gravity.START
        barParams.width = width
        barParams.height = barHeight
        barParams.x = overviewX
        barParams.y = overviewY + contentHeight
        val stripParams = overviewStripParams
        val strip = overviewStrip
        if (stripParams != null && strip != null) {
            stripParams.gravity = Gravity.BOTTOM or Gravity.START
            stripParams.width = dp(OVERVIEW_STRIP_WIDTH_DP)
            stripParams.height = (contentHeight - dp(16)).coerceAtLeast(dp(60))
            stripParams.x = overviewX + width - dp(OVERVIEW_STRIP_WIDTH_DP) - dp(6)
            stripParams.y = overviewY + dp(8)
            runCatching { windowManager.updateViewLayout(strip, stripParams) }
        }
        runCatching { windowManager.updateViewLayout(content, contentParams) }
        runCatching { windowManager.updateViewLayout(bar, barParams) }
    }

    /** Brings the sheet back to its default spot and size (used by the "答" button). */
    private fun resetOverviewOnMain() {
        if (overviewView == null || overviewBar == null) {
            showOverviewOnMain(lastAnswers)
            return
        }
        val mini = (screenHeight() * OVERVIEW_DEFAULT_HEIGHT_PERCENT / 100).coerceAtLeast(minPanelHeight())
        val expanded = (screenHeight() * OVERVIEW_EXPANDED_HEIGHT_PERCENT / 100).coerceAtLeast(minPanelHeight())
        // "答" toggles between the贴底 mini strip (page stays readable) and the full list.
        val target = if (overviewPanelHeight > mini + dp(8)) mini else expanded
        overviewX = dp(12)
        overviewY = dp(12)
        overviewPanelHeight = target
        applyOverviewGeometry()
    }

    /** Dragging the title only moves the whole sheet; the size is left untouched. */
    private inner class TranslateWindowListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = overviewX
                    startY = overviewY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        // y is the offset from the bottom edge, so a larger y lifts the sheet.
                        val maxLift = (screenHeight() - overviewPanelHeight - statusBarHeight() - dp(8))
                            .coerceAtLeast(0)
                        overviewY = (startY - dy.roundToInt()).coerceIn(0, maxLift)
                        overviewX = (startX + dx.roundToInt())
                            .coerceIn(0, (screenWidth() - dp(MIN_VISIBLE_DP)).coerceAtLeast(0))
                        applyOverviewGeometry()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> return true
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    /** Dragging the "⇕" handle only resizes the sheet; its position is left untouched. */
    private inner class ResizeWindowListener : View.OnTouchListener {
        private var downRawY = 0f
        private var startHeight = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startHeight = overviewPanelHeight
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downRawY
                    if (!dragging && abs(dy) > touchSlop) dragging = true
                    if (dragging) {
                        val minHeight = minPanelHeight()
                        val maxHeight = (screenHeight() * OVERVIEW_MAX_HEIGHT_PERCENT / 100).coerceAtLeast(minHeight)
                        overviewPanelHeight = (startHeight - dy.roundToInt()).coerceIn(minHeight, maxHeight)
                        // Keep the top edge on screen if the sheet had been lifted earlier.
                        val maxLift = (screenHeight() - overviewPanelHeight - statusBarHeight() - dp(8))
                            .coerceAtLeast(0)
                        overviewY = overviewY.coerceIn(0, maxLift)
                        applyOverviewGeometry()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> return true
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun overviewRow(answer: SweptAnswer): View {
        val header = styledText(sizeSp = 13f, color = COLOR_PANEL_HEAD, bold = true)
        header.text = "第 ${answer.questionNo} 题 · ${typeLabel(answer.type)}"
        header.setShadowLayer(3f, 0f, 0f, Color.argb(220, 255, 255, 255))
        val body = styledText(sizeSp = 13f, color = COLOR_PANEL_INK).apply {
            setPadding(0, dp(4), 0, 0)
            text = buildOverviewBody(answer)
            // A white glow keeps the text readable over the translucent background.
            setShadowLayer(3f, 0f, 0f, Color.argb(220, 255, 255, 255))
        }
        return LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(header, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(body, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun buildOverviewBody(answer: SweptAnswer): String = buildString {
        val bankHit = answer.source == "question_bank"
        if (!answer.question.isNullOrBlank()) append(answer.question.trim())
        if (!bankHit) {
            answer.options.sortedBy { option -> option.index }.forEach { option ->
                val correct = option.index in answer.answerIndices
                append("\n")
                if (correct) append("✓ ")
                append(answerLabel(option.index)).append(". ").append(option.text.trim())
            }
        }
        if (answer.error != null) {
            append("\n识别失败：").append(answer.error)
            return@buildString
        }
        val letters = answer.answerIndices.sorted().joinToString("、") { index -> answerLabel(index) }
        append("\n答案：")
        when {
            answer.answerText.isNotBlank() -> append(answer.answerText.trim())
            letters.isNotBlank() -> append(letters)
            else -> append("--")
        }
        answer.matchLabel?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
        if (answer.answerText.isBlank()) {
            answer.options
                .filter { option -> option.index in answer.answerIndices }
                .sortedBy { option -> option.index }
                .forEach { option ->
                    append("\n").append(answerLabel(option.index)).append(". ").append(option.text.trim())
                }
        }
    }

    private fun hideOverviewOnMain() {
        val content = overviewView
        val bar = overviewBar
        val strip = overviewStrip
        overviewView = null
        overviewParams = null
        overviewBar = null
        overviewBarParams = null
        overviewStrip = null
        overviewStripParams = null
        overviewPanelHeight = 0
        listOfNotNull(content, bar, strip).forEach { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
                // Already removed by the system.
            }
        }
    }

    private fun createMarker() {
        val label = TextView(appContext).apply {
            text = "跳"
            gravity = Gravity.CENTER
            setTextColor(COLOR_DEEP_GREEN)
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
        val root = FrameLayout(appContext).apply {
            background = roundedBackground(COLOR_ORANGE, markerSize / 2f)
            elevation = dp(9).toFloat()
            alpha = 0.9f
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            setOnTouchListener(MarkerTouchListener())
        }
        val params = WindowManager.LayoutParams(
            markerSize,
            markerSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_MARKER_X, screenWidth() / 2)
            y = prefs.getInt(KEY_MARKER_Y, (screenHeight() * 9 / 10).coerceAtMost(screenHeight() - markerSize - edgeMargin))
        }
        try {
            windowManager.addView(root, params)
            markerView = root
            markerParams = params
        } catch (_: Exception) {
            markerView = null
            markerParams = null
        }
    }

    private fun removeMarker() {
        val view = markerView ?: return
        markerView = null
        markerParams = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            // Already removed by the system.
        }
    }

    private fun persistMarker() {
        val params = markerParams ?: return
        prefs.edit().putInt(KEY_MARKER_X, params.x).putInt(KEY_MARKER_Y, params.y).apply()
    }

    private fun createAnswerButton() {
        val label = TextView(appContext).apply {
            text = "答"
            gravity = Gravity.CENTER
            setTextColor(COLOR_LIGHT)
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
        val root = FrameLayout(appContext).apply {
            background = roundedBackground(COLOR_DEEP_GREEN, markerSize / 2f)
            elevation = dp(9).toFloat()
            alpha = 0.92f
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            setOnClickListener { toggleOverview() }
            setOnTouchListener(AnswerButtonTouchListener())
        }
        val params = WindowManager.LayoutParams(
            markerSize,
            markerSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = edgeMargin
            y = (screenHeight() * 6 / 10).coerceAtLeast(edgeMargin)
        }
        try {
            windowManager.addView(root, params)
            answerButtonView = root
            answerButtonParams = params
        } catch (_: Exception) {
            answerButtonView = null
            answerButtonParams = null
        }
    }

    private fun removeAnswerButton() {        val view = answerButtonView ?: return
        answerButtonView = null
        answerButtonParams = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            // Already removed by the system.
        }
    }

    private inner class AnswerButtonTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = answerButtonParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx.roundToInt())
                            .coerceIn(edgeMargin, (screenWidth() - markerSize - edgeMargin).coerceAtLeast(edgeMargin))
                        params.y = (startY + dy.roundToInt())
                            .coerceIn(edgeMargin, (screenHeight() - markerSize - edgeMargin).coerceAtLeast(edgeMargin))
                        updateBubbleLayout(view, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private inner class MarkerTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = markerParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx.roundToInt())
                            .coerceIn(edgeMargin, (screenWidth() - markerSize - edgeMargin).coerceAtLeast(edgeMargin))
                        params.y = (startY + dy.roundToInt())
                            .coerceIn(edgeMargin, (screenHeight() - markerSize - edgeMargin).coerceAtLeast(edgeMargin))
                        updateBubbleLayout(view, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) persistMarker()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    companion object {
        const val AUTO_COLLAPSE_MS = 1_000L
        private const val CAPTURE_HIDE_DELAY_MS = 180L
        private const val BUBBLE_SIZE_DP = 56
        private const val MARKER_SIZE_DP = 44
        private const val MIN_VISIBLE_DP = 44
        private const val OVERVIEW_MIN_HEIGHT_DP = 56
        private const val OVERVIEW_BAR_HEIGHT_DP = 44
        private const val OVERVIEW_STRIP_WIDTH_DP = 40
        private const val OVERVIEW_DEFAULT_HEIGHT_PERCENT = 35
        private const val OVERVIEW_EXPANDED_HEIGHT_PERCENT = 70
        private const val OVERVIEW_MAX_HEIGHT_PERCENT = 70
        private val COLOR_PANEL_BG = Color.argb(242, 252, 251, 247)
        private val COLOR_PANEL_BAR_BG = Color.argb(235, 252, 251, 247)
        private val COLOR_PANEL_INK = Color.rgb(28, 38, 34)
        private val COLOR_PANEL_HEAD = Color.rgb(32, 96, 68)
        private val COLOR_PANEL_ACTION = Color.rgb(32, 96, 68)
        private const val MARKER_HIDE_DELAY_MS = 90L
        private const val MARKER_RESTORE_DELAY_MS = 320L
        private const val PREFS_NAME = "tijing-overlay"
        private const val KEY_MARKER_X = "marker-x"
        private const val KEY_MARKER_Y = "marker-y"
        private const val EDGE_MARGIN_DP = 8
        private const val TOUCH_SLOP_DP = 5
        private const val LONG_PRESS_MS = 600L
        private const val CARD_CORNER_DP = 8f
        private const val MAX_ERROR_LENGTH = 240
        private val COLOR_DEEP_GREEN = Color.rgb(20, 34, 31)
        private val COLOR_ACCENT = Color.rgb(198, 244, 119)
        private val COLOR_LIGHT = Color.rgb(246, 242, 233)
        private val COLOR_ORANGE = Color.rgb(242, 139, 93)
        private val COLOR_MUTED = Color.rgb(194, 204, 197)
    }
}
