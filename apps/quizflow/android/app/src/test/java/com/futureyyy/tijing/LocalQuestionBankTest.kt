package com.futureyyy.tijing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalQuestionBankTest {
    @Test
    fun exactMatchIgnoresWhitespaceAndPunctuation() {
        val index = QuestionBankIndex.fromRecords(
            listOf(record("q-1", "智能客服占比的目标值是多少？")),
        )

        val result = index.match("智能客服占比 的目标值是 多少")

        assertEquals(QuestionBankMatchStatus.EXACT, result.status)
        assertEquals("q-1", result.record?.id)
    }

    @Test
    fun keyPhraseMatchAcceptsAnOcrLikeStemWithoutSelectingOptions() {
        val index = QuestionBankIndex.fromRecords(
            listOf(record("q-1", "通过智能化技术提升问题识别和解决能力，减少误判与遗漏。")),
        )

        val result = index.match("智能化技术提升问题识别和解决能力减少误判与遗漏")

        assertEquals(QuestionBankMatchStatus.KEY_PHRASE, result.status)
        assertEquals("q-1", result.record?.id)
    }

    @Test
    fun ambiguousStemDoesNotReturnASingleAnswer() {
        val index = QuestionBankIndex.fromRecords(
            listOf(
                record("q-1", "智能客服业务目标包括哪些内容？"),
                record("q-2", "智能客服业务目标包括哪些重点内容？"),
            ),
        )

        val result = index.match("智能客服业务目标包括哪些")

        assertEquals(QuestionBankMatchStatus.AMBIGUOUS, result.status)
        assertNull(result.record)
    }

    @Test
    fun knownConflictIsReportedBeforeAnswerLookup() {
        val index = QuestionBankIndex.fromRecords(
            listOf(record("q-1", "同一题干")),
            conflictQuestions = setOf("同一题干"),
        )

        val result = index.match("同一题干")

        assertEquals(QuestionBankMatchStatus.CONFLICT, result.status)
        assertNull(result.record)
    }

    @Test
    fun stemParserRequiresOnlyTheQuestionText() {
        assertEquals("识别题干", QuestionStemParser.parse("{\"question\":\"识别题干\"}"))
    }

    @Test
    fun matchResultCanBeConsumedByTheExistingOverlayModelContract() {
        val index = QuestionBankIndex.fromRecords(
            listOf(record("q-1", "标准题干")),
        )
        val match = index.match("标准题干")

        val parsed = QuizModelParser.parse(index.toModelJson(match, "标准题干", 7))

        assertEquals(7, parsed.questionNo)
        assertEquals(listOf(0), parsed.answerIndices)
        assertEquals("exact", parsed.matchStatus)
    }

    @Test
    fun bankResponseIgnoresLegacyMultipleAnswersWhenStemIsNotFound() {
        val index = QuestionBankIndex.fromRecords(
            listOf(record("q-1", "另一道题")),
        )
        val legacyVisualResponse = """
            {
              "question_type": "single",
              "question": "当前题库没有这道题",
              "options": [{"index": 0, "text": "A"}, {"index": 1, "text": "B"}],
              "answer_indices": [0, 1],
              "confidence": 0.9
            }
        """.trimIndent()

        val bankJson = QuestionBankResponseAdapter.toModelJson(
            raw = legacyVisualResponse,
            index = index,
            questionNo = 3,
        )
        val parsed = QuizModelParser.parse(bankJson)

        assertEquals("not_found", parsed.matchStatus)
        assertEquals(emptyList<Int>(), parsed.answerIndices)
    }

    @Test
    fun onlyUnresolvedBankStatusesUseTheVisualAnswerFallback() {
        assertEquals(false, QuestionBankResponseAdapter.shouldUseVisionFallback(QuestionBankMatchStatus.EXACT))
        assertEquals(false, QuestionBankResponseAdapter.shouldUseVisionFallback(QuestionBankMatchStatus.KEY_PHRASE))
        assertEquals(true, QuestionBankResponseAdapter.shouldUseVisionFallback(QuestionBankMatchStatus.NOT_FOUND))
        assertEquals(true, QuestionBankResponseAdapter.shouldUseVisionFallback(QuestionBankMatchStatus.AMBIGUOUS))
        assertEquals(true, QuestionBankResponseAdapter.shouldUseVisionFallback(QuestionBankMatchStatus.CONFLICT))
    }

    private fun record(id: String, question: String) = LocalQuestionBankRecord(
        id = id,
        question = question,
        type = QuestionType.SINGLE,
        answerIndices = listOf(0),
        options = listOf(QuizOption(0, "答案", null)),
    )
}
