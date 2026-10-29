package com.futureyyy.tijing

import org.junit.Assert.*
import org.junit.Test

class PopupHandlingTest {
    private val button = NormalizedBox(25f, 60f, 50f, 8f)
    private val next = NormalizedBox(85f, 90f, 10f, 5f)
    private fun popup(kind: String = "notice", message: String = "加载完成", label: String = "我知道了") =
        PopupObservation(kind, "提示", message, label, button)
    private fun question() = FlowObservation(FlowPage.QUESTION, 0.99, "1", "题干", true,
        listOf(FlowOption("保护性", button, true)), listOf("保护性"), confirm = button, next = next)

    @Test fun `ordinary notice is closed by its own button`() {
        assertEquals(FlowAction.Tap(button, "关闭提示弹窗"), PopupHandling.decide(popup(), false))
    }

    @Test fun `network retry requires recognizable failure and retry label`() {
        assertEquals(FlowAction.Tap(button, "重试页面加载"), PopupHandling.decide(popup("network_error", "网络连接失败", "重试"), false))
        assertTrue(PopupHandling.decide(popup("network_error", "连接失败", "确定"), false) is FlowAction.Pause)
    }

    @Test fun `unknown dialogs pause instead of choosing a default button`() {
        assertTrue(PopupHandling.decide(popup("unknown"), false) is FlowAction.Pause)
    }

    @Test fun `login permissions navigation and final submission require manual handling`() {
        for (kind in listOf("login", "permission", "navigation_warning", "submission")) {
            assertTrue(PopupHandling.decide(popup(kind), true) is FlowAction.Pause)
        }
    }

    @Test fun `misclassified notice mentioning final submission is not confirmed`() {
        assertTrue(PopupHandling.decide(popup(message = "确定要交卷吗？", label = "确定"), true) is FlowAction.Pause)
        assertTrue(PopupHandling.decide(popup(message = "离开页面会丢失未保存答案", label = "确认"), true) is FlowAction.Pause)
    }

    @Test fun `feedback cannot be accepted before any answer action`() {
        assertTrue(PopupHandling.decide(popup("answer_feedback", "回答正确", "确定"), false) is FlowAction.Pause)
    }

    @Test fun `after closing confirmed feedback use next arrow not confirmation again`() {
        val p = AutomationPolicy()
        assertEquals(FlowAction.Confirm(button, 1), p.decide(question()))
        assertEquals(FlowAction.Tap(button, "关闭答题反馈"), p.decide(FlowObservation(FlowPage.POPUP, 0.99,
            popup = popup("answer_feedback", "本题已提交，回答正确", "确定"))))
        assertEquals(FlowAction.Tap(next, "下一题"), p.decide(question()))
    }

    @Test fun `failed submission popup does not mark the question as confirmed`() {
        val p = AutomationPolicy(); p.decide(question())
        p.decide(FlowObservation(FlowPage.POPUP, 0.99, popup = popup("answer_feedback", "答案提交失败", "确定")))
        assertEquals(FlowAction.Confirm(button, 2), p.decide(question()))
        assertFalse(PopupHandling.confirmsAnswer(popup("answer_feedback", "答案提交失败")))
    }

    @Test fun `repeated popup handling stops after three attempts`() {
        val p = AutomationPolicy()
        val o = FlowObservation(FlowPage.POPUP, 0.99, popup = popup())
        repeat(3) { assertTrue(p.decide(o) is FlowAction.Tap) }
        assertTrue(p.decide(o) is FlowAction.Pause)
    }

    @Test fun `missing or invalid popup location is never clicked`() {
        assertTrue(PopupHandling.decide(popup().copy(button = null), true) is FlowAction.Pause)
    }

    @Test fun `popup parsing does not require the obscured question`() {
        val o = FlowObservation.parse("""{"page_kind":"popup","confidence":0.99,"popup_kind":"notice","popup_title":"提示","popup_message":"加载完成","popup_button_text":"关闭","popup_button_box":[25,60,50,8]}""")
        assertEquals(FlowAction.Tap(button, "关闭提示弹窗"), AutomationPolicy().decide(o))
    }
}
