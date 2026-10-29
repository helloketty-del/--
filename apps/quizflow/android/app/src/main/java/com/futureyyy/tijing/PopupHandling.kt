package com.futureyyy.tijing

data class PopupObservation(
    val kind: String,
    val title: String,
    val message: String,
    val buttonText: String,
    val button: NormalizedBox?,
)

object PopupHandling {
    private val riskWords = listOf("交卷", "结束考试", "退出考试", "离开页面", "离开考试", "清空", "删除", "放弃", "未保存", "未作答", "未答题", "授权", "权限", "登录", "密码", "验证码", "支付")
    private val closeButtons = setOf("关闭", "取消", "稍后", "暂不", "知道了", "我知道了", "确定", "确认")
    private val feedbackEvidence = listOf("正确", "错误", "解析", "已提交", "作答成功", "提交成功", "答案")

    fun confirmsAnswer(popup: PopupObservation?): Boolean {
        if (popup?.kind != "answer_feedback") return false
        val text = popup.title + popup.message
        if (listOf("提交失败", "作答失败", "未提交", "未成功", "网络").any(text::contains)) return false
        return listOf("回答正确", "回答错误", "正确答案", "已提交", "作答成功", "提交成功", "答案解析", "答题结果").any(text::contains)
    }

    fun decide(popup: PopupObservation?, answerActionStarted: Boolean): FlowAction {
        if (popup == null) return FlowAction.Pause("出现弹窗，但未识别到弹窗内容")
        val content = popup.title + popup.message
        if (popup.kind in setOf("permission", "login", "navigation_warning", "submission") || riskWords.any(content::contains)) {
            return FlowAction.Pause("弹窗需要人工处理：${popup.title.ifBlank { popup.kind }}")
        }
        val label = popup.buttonText.trim()
        val purpose = when (popup.kind) {
            "answer_feedback" -> {
                if (!answerActionStarted || feedbackEvidence.none(content::contains) || label !in closeButtons + "下一题") {
                    return FlowAction.Pause("无法确认该弹窗是本题答题反馈，请人工核对")
                }
                "关闭答题反馈"
            }
            "network_error" -> {
                if (listOf("网络", "连接", "加载失败", "超时").none(content::contains) || label !in setOf("重试", "重新加载", "刷新")) {
                    return FlowAction.Pause("网络弹窗的操作不明确，请人工处理")
                }
                "重试页面加载"
            }
            "notice" -> {
                if (content.isBlank() || label !in closeButtons) return FlowAction.Pause("提示弹窗的作用不明确，请人工处理")
                "关闭提示弹窗"
            }
            else -> return FlowAction.Pause("出现未知弹窗，请人工处理后继续")
        }
        val box = popup.button ?: return FlowAction.Pause("弹窗按钮位置不清楚，请人工处理")
        return FlowAction.Tap(box, purpose)
    }
}
