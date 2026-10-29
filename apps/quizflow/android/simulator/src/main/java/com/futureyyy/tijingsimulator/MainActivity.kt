package com.futureyyy.tijingsimulator

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val selectedAnswers = linkedSetOf<Int>()
    private val selectedAnswerHistory = mutableMapOf<Int, Set<Int>>()
    private val fillAnswerHistory = mutableMapOf<Int, String>()
    private val optionViews = mutableListOf<TextView>()
    private val markerViews = mutableListOf<View>()

    private lateinit var questionCounter: TextView
    private lateinit var typeBadge: TextView
    private lateinit var questionText: TextView
    private lateinit var optionsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var confirmButton: Button
    private var answerInput: EditText? = null
    private var questionIndex = 0
    private var submitting = false
    private var completed = false
    private var sessionSeed = System.currentTimeMillis()
    private var bankQuestions: List<SimulatorQuestion> = emptyList()
    private var sessionQuestions: List<SimulatorQuestion> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val screen = buildScreen()
        setContentView(screen)
        screen.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    top = insets.systemWindowInsetTop
                    bottom = insets.systemWindowInsetBottom
                }
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        screen.requestApplyInsets()
        bankQuestions = runCatching { SimulatorQuestionBank.loadFromAsset(this) }
            .getOrDefault(SimulatorQuestionBank.questions)
        sessionQuestions = bankQuestions
        resetSession()
        renderQuestion()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            contentDescription = "题库模拟器题目页"
        }
        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(10))
        }

        questionCounter = textView(13f, COLOR_MUTED).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        content.addView(questionCounter)

        val progressTrack = View(this).apply { background = rounded(COLOR_TRACK, 2f) }
        content.addView(progressTrack, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(4),
        ).apply { topMargin = dp(6) })

        val markerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        repeat(MARKER_COUNT) {
            markerRow.addView(View(this).also(markerViews::add), LinearLayout.LayoutParams(0, dp(8), 1f).apply {
                if (it > 0) leftMargin = dp(2)
            })
        }
        content.addView(markerRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(8),
        ).apply { topMargin = dp(10) })

        typeBadge = textView(12f, COLOR_DEEP).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(COLOR_BADGE, 4f)
        }
        content.addView(typeBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })

        questionText = textView(20f, COLOR_INK).apply {
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        content.addView(questionText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        optionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionsContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        root.addView(buildActionPanel())
        root.addView(buildBottomNavigation())
        return root
    }

    private fun buildActionPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(5), dp(20), dp(7))
            background = rounded(COLOR_SURFACE, 0f)
        }
        statusText = textView(13f, COLOR_MUTED).apply {
            gravity = Gravity.CENTER
            minHeight = dp(24)
        }
        panel.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        confirmButton = Button(this).apply {
            text = "确认"
            contentDescription = "确认"
            isAllCaps = false
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(COLOR_ORANGE, 25f)
            setOnClickListener { submitCurrentQuestion() }
        }
        panel.addView(confirmButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50),
        ).apply { topMargin = dp(2) })
        return panel
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(10), dp(8))
            background = rounded(COLOR_SURFACE, 0f)
        }
        val title = textView(16f, COLOR_INK).apply {
            text = "题库模拟器"
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = textView(9f, COLOR_MUTED).apply {
            text = "AUTO FLOW FIXTURE"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(1) })
        }
        bar.addView(titleStack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton(android.R.drawable.ic_popup_sync, "重置题库") {
            resetSession()
            renderQuestion()
        })
        return bar
    }

    private fun buildBottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(4), dp(28), dp(6))
            background = rounded(COLOR_SURFACE, 0f)
        }
        bar.addView(iconButton(android.R.drawable.ic_media_previous, "上一题") { moveBy(-1) })
        val spacer = View(this)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        val hint = textView(11f, COLOR_MUTED).apply {
            text = "模拟题库 · 点击确认自动换题"
        }
        bar.addView(hint)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(iconButton(android.R.drawable.ic_media_next, "下一题") { moveBy(1) })
        return bar
    }

    private fun renderQuestion() {
        val question = currentQuestion()
        selectedAnswers.clear()
        selectedAnswers.addAll(selectedAnswerHistory[question.id].orEmpty())
        val visibleQuestionNumber = questionIndex + 1
        questionCounter.text = "连续测试 · 第 $visibleQuestionNumber 题 / ${sessionQuestions.size}"
        questionCounter.contentDescription = "第 $visibleQuestionNumber 题，共 ${sessionQuestions.size} 题，随机种子 $sessionSeed"
        typeBadge.text = when (question.type) {
            SimulatorQuestionType.SINGLE -> "单选题"
            SimulatorQuestionType.MULTIPLE -> "多选题"
            SimulatorQuestionType.INDEFINITE -> "不定项选择题"
            SimulatorQuestionType.TRUE_FALSE -> "判断题"
            SimulatorQuestionType.FILL_BLANK -> "填空题"
        }
        questionText.text = question.prompt
        statusText.setTextColor(COLOR_MUTED)
        statusText.text = when (question.type) {
            SimulatorQuestionType.MULTIPLE -> "可选择多个选项后点击确认"
            SimulatorQuestionType.INDEFINITE -> "可选择一个或多个选项后点击确认"
            SimulatorQuestionType.FILL_BLANK -> "填写答案后点击确认"
            else -> "选择一个选项后点击确认"
        }
        updateMarker()
        renderOptions(question)
        confirmButton.isEnabled = true
        confirmButton.alpha = 1f
        if (completed) {
            confirmButton.isEnabled = false
            confirmButton.alpha = 0.62f
            statusText.setTextColor(COLOR_ORANGE_DARK)
            statusText.text = "本轮 ${sessionQuestions.size} 题已完成，可重置开始新一轮"
        }
    }

    private fun renderOptions(question: SimulatorQuestion) {
        optionViews.clear()
        optionsContainer.removeAllViews()
        answerInput = null
        if (question.type == SimulatorQuestionType.FILL_BLANK) {
            val input = EditText(this).apply {
                hint = "请输入填空答案"
                textSize = 18f
                setText(fillAnswerHistory[question.id].orEmpty())
                setPadding(dp(16), dp(8), dp(16), dp(8))
                minHeight = dp(56)
                contentDescription = "填空答案"
            }
            answerInput = input
            optionsContainer.addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
            return
        }
        question.options.forEachIndexed { index, option ->
            val optionView = textView(18f, COLOR_INK).apply {
                text = "${('A'.code + index).toChar()}. $option"
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(56)
                setPadding(dp(16), dp(8), dp(14), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = "${('A'.code + index).toChar()}选项 $option"
                setOnClickListener {
                    if (!submitting) toggleAnswer(index, question)
                }
            }
            optionViews += optionView
            optionsContainer.addView(optionView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
        updateOptionStyles()
    }

    private fun toggleAnswer(index: Int, question: SimulatorQuestion) {
        if (question.type != SimulatorQuestionType.MULTIPLE && question.type != SimulatorQuestionType.INDEFINITE) selectedAnswers.clear()
        if (!selectedAnswers.add(index)) selectedAnswers.remove(index)
        selectedAnswerHistory[question.id] = selectedAnswers.toSet()
        updateOptionStyles()
        statusText.setTextColor(COLOR_MUTED)
        statusText.text = "已选择 ${selectedAnswers.size} 个选项，点击确认进入下一题"
    }

    private fun updateOptionStyles() {
        optionViews.forEachIndexed { index, view ->
            val selected = index in selectedAnswers
            view.background = rounded(
                if (selected) COLOR_SELECTED else COLOR_OPTION,
                3f,
                if (selected) COLOR_SELECTED_STROKE else COLOR_OPTION_STROKE,
                if (selected) 2 else 1,
            )
            view.isEnabled = !submitting
            view.alpha = if (submitting) 0.72f else 1f
        }
    }

    private fun submitCurrentQuestion() {
        if (submitting) return
        val question = currentQuestion()
        if (question.type == SimulatorQuestionType.FILL_BLANK) {
            val answer = answerInput?.text?.toString()?.trim().orEmpty()
            if (answer.isEmpty()) {
                statusText.setTextColor(COLOR_ERROR)
                statusText.text = "请先填写答案"
                return
            }
            fillAnswerHistory[question.id] = answer
        } else if (selectedAnswers.isEmpty()) {
            statusText.setTextColor(COLOR_ERROR)
            statusText.text = "请先选择至少一个答案"
            return
        }
        submitting = true
        updateOptionStyles()
        confirmButton.isEnabled = false
        confirmButton.alpha = 0.62f
        statusText.setTextColor(COLOR_ORANGE_DARK)
        statusText.text = "已确认，正在进入下一题..."
        mainHandler.postDelayed({
            if (questionIndex == sessionQuestions.lastIndex) {
                submitting = false
                completed = true
                confirmButton.isEnabled = false
                confirmButton.alpha = 0.62f
                statusText.setTextColor(COLOR_ORANGE_DARK)
                statusText.text = "本轮 ${sessionQuestions.size} 题已完成，可重置开始新一轮"
            } else {
                questionIndex += 1
                selectedAnswers.clear()
                submitting = false
                renderQuestion()
            }
        }, PAGE_SWITCH_DELAY_MS)
    }

    private fun moveBy(delta: Int) {
        if (submitting) return
        val question = currentQuestion()
        if (question.type == SimulatorQuestionType.FILL_BLANK) {
            fillAnswerHistory[question.id] = answerInput?.text?.toString()?.trim().orEmpty()
        } else {
            selectedAnswerHistory[question.id] = selectedAnswers.toSet()
        }
        questionIndex = (questionIndex + delta).coerceIn(0, sessionQuestions.lastIndex)
        completed = false
        selectedAnswers.clear()
        renderQuestion()
    }

    private fun updateMarker() {
        val seed = (questionIndex * 37 + 11)
        markerViews.forEachIndexed { index, marker ->
            val on = ((seed shr (index % 8)) xor (index * 13)) and 1 == 0
            marker.background = rounded(if (on) COLOR_DEEP else COLOR_ORANGE, 1f)
        }
    }

    private fun currentQuestion(): SimulatorQuestion = sessionQuestions[questionIndex]

    private fun resetSession() {
        sessionSeed = System.currentTimeMillis()
        sessionQuestions = SimulatorQuestionBank.randomSession(
            if (bankQuestions.isEmpty()) SimulatorQuestionBank.questions else bankQuestions,
            count = 100,
            seed = sessionSeed,
        )
        questionIndex = 0
        completed = false
        selectedAnswers.clear()
        selectedAnswerHistory.clear()
        fillAnswerHistory.clear()
        answerInput = null
        submitting = false
    }

    private fun iconButton(icon: Int, description: String, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(COLOR_ORANGE)
            contentDescription = description
            background = rounded(COLOR_SURFACE, 20f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { action() }
        }

    private fun textView(sizeSp: Float, color: Int): TextView = TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        includeFontPadding = false
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidthDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_SWITCH_DELAY_MS = 520L
        private const val MARKER_COUNT = 16
        private val COLOR_BACKGROUND = Color.rgb(247, 247, 245)
        private val COLOR_SURFACE = Color.WHITE
        private val COLOR_INK = Color.rgb(37, 48, 45)
        private val COLOR_DEEP = Color.rgb(27, 45, 39)
        private val COLOR_MUTED = Color.rgb(111, 124, 117)
        private val COLOR_TRACK = Color.rgb(226, 229, 224)
        private val COLOR_BADGE = Color.rgb(255, 226, 216)
        private val COLOR_ORANGE = Color.rgb(251, 91, 48)
        private val COLOR_ORANGE_DARK = Color.rgb(219, 72, 35)
        private val COLOR_OPTION = Color.rgb(255, 255, 255)
        private val COLOR_OPTION_STROKE = Color.rgb(222, 226, 220)
        private val COLOR_SELECTED = Color.rgb(255, 242, 238)
        private val COLOR_SELECTED_STROKE = Color.rgb(239, 93, 70)
        private val COLOR_ERROR = Color.rgb(186, 61, 45)
    }
}
