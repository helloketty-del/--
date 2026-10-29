package com.futureyyy.tijingsimulator

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Random

enum class SimulatorQuestionType {
    SINGLE,
    MULTIPLE,
    INDEFINITE,
    TRUE_FALSE,
    FILL_BLANK,
}

data class SimulatorQuestion(
    val id: Int,
    val type: SimulatorQuestionType,
    val prompt: String,
    val options: List<String>,
    val correctAnswers: Set<Int>,
    val answerText: String = "",
)

object SimulatorQuestionBank {
    private const val QUESTION_COUNT = 100

    private val seedQuestions = listOf(
        SimulatorQuestion(
            id = 1,
            type = SimulatorQuestionType.MULTIPLE,
            prompt = "数字员工运营管控与迭代中，5.2 数据监控的两个层面是什么？",
            options = listOf("集团统一监控平台", "本级监控平台", "第三方监控平台", "用户端监控"),
            correctAnswers = setOf(0, 1),
        ),
        SimulatorQuestion(
            id = 2,
            type = SimulatorQuestionType.MULTIPLE,
            prompt = "话务质检数字员工的业务效能指标包括以下哪些？",
            options = listOf("语音质检量", "10000 号呼入话务覆盖率", "语音质检准确率", "话务小结打标量", "外呼接通率"),
            correctAnswers = setOf(0, 1, 2, 3),
        ),
        SimulatorQuestion(
            id = 3,
            type = SimulatorQuestionType.TRUE_FALSE,
            prompt = "数字员工上线后，仍应持续监控运行质量并根据反馈迭代优化。",
            options = listOf("正确", "错误"),
            correctAnswers = setOf(0),
        ),
        SimulatorQuestion(
            id = 4,
            type = SimulatorQuestionType.SINGLE,
            prompt = "在自动化流程运行前，首先需要校验的内容是？",
            options = listOf("运行权限与目标页面", "历史题目数量", "设备主题颜色", "通知铃声音量"),
            correctAnswers = setOf(0),
        ),
        SimulatorQuestion(
            id = 5,
            type = SimulatorQuestionType.MULTIPLE,
            prompt = "模型返回答案动作前，应完成哪些基础校验？",
            options = listOf("题型有效", "选项编号有效", "点击框坐标有效", "置信度在范围内"),
            correctAnswers = setOf(0, 1, 2, 3),
        ),
        SimulatorQuestion(
            id = 6,
            type = SimulatorQuestionType.TRUE_FALSE,
            prompt = "多选题只需要点击第一个正确选项后即可确认。",
            options = listOf("正确", "错误"),
            correctAnswers = setOf(1),
        ),
        SimulatorQuestion(
            id = 7,
            type = SimulatorQuestionType.SINGLE,
            prompt = "页面换题检测使用的主要依据是？",
            options = listOf("页面内容指纹变化并稳定", "按钮颜色固定", "手机电量变化", "网络信号强度"),
            correctAnswers = setOf(0),
        ),
        SimulatorQuestion(
            id = 8,
            type = SimulatorQuestionType.MULTIPLE,
            prompt = "以下哪些属于自动答题流程中的阶段？",
            options = listOf("截图", "模型识别", "选择答案", "确认换页", "人工录音"),
            correctAnswers = setOf(0, 1, 2, 3),
        ),
        SimulatorQuestion(
            id = 9,
            type = SimulatorQuestionType.SINGLE,
            prompt = "当页面长时间未变化时，自动流程应进入什么状态？",
            options = listOf("暂停并提示处理", "无限重复点击", "自动卸载应用", "清空所有设置"),
            correctAnswers = setOf(0),
        ),
        SimulatorQuestion(
            id = 10,
            type = SimulatorQuestionType.TRUE_FALSE,
            prompt = "题库模拟器中的确认按钮会在点击后自动切换下一题。",
            options = listOf("正确", "错误"),
            correctAnswers = setOf(0),
        ),
    )

    val questions: List<SimulatorQuestion> = List(QUESTION_COUNT) { index ->
        val seed = seedQuestions[index % seedQuestions.size]
        seed.copy(
            id = index + 1,
            // The question number makes every simulated page distinct for transition detection.
            prompt = "模拟测试题 ${index + 1}：${seed.prompt}",
        )
    }

    fun questionAt(index: Int): SimulatorQuestion = questions[Math.floorMod(index, questions.size)]

    fun randomSession(source: List<SimulatorQuestion>, count: Int = QUESTION_COUNT, seed: Long): List<SimulatorQuestion> {
        require(count > 0) { "count 必须大于 0" }
        return source.shuffled(Random(seed)).take(count.coerceAtMost(source.size))
    }

    fun loadFromAsset(context: Context): List<SimulatorQuestion> {
        return context.assets.open("question-bank.json").bufferedReader(Charsets.UTF_8).use { reader ->
            val asset = Gson().fromJson(reader, BankAsset::class.java)
            asset.questions.mapIndexed { index, question ->
                SimulatorQuestion(
                    id = index + 1,
                    type = when (question.type) {
                        "multiple" -> SimulatorQuestionType.MULTIPLE
                        "indefinite" -> SimulatorQuestionType.INDEFINITE
                        "true_false" -> SimulatorQuestionType.TRUE_FALSE
                        "fill_blank" -> SimulatorQuestionType.FILL_BLANK
                        else -> SimulatorQuestionType.SINGLE
                    },
                    prompt = question.question,
                    options = question.options.map(BankOption::text),
                    correctAnswers = question.answerIndices.toSet(),
                    answerText = question.answerText.orEmpty(),
                )
            }
        }
    }

    private data class BankAsset(
        val questions: List<BankQuestion> = emptyList(),
    )

    private data class BankQuestion(
        val type: String = "single",
        val question: String = "",
        val options: List<BankOption> = emptyList(),
        @SerializedName("answer_indices") val answerIndices: List<Int> = emptyList(),
        @SerializedName("answer_text") val answerText: String? = null,
    )

    private data class BankOption(
        val text: String = "",
    )
}
