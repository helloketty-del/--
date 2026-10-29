package com.futureyyy.tijingsimulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorQuestionBankTest {
    @Test
    fun questionBankContainsAllSupportedQuestionTypesAndValidAnswers() {
        assertEquals(100, SimulatorQuestionBank.questions.size)
        val types = SimulatorQuestionBank.questions.map { it.type }.toSet()

        assertTrue(types.contains(SimulatorQuestionType.SINGLE))
        assertTrue(types.contains(SimulatorQuestionType.MULTIPLE))
        assertTrue(types.contains(SimulatorQuestionType.TRUE_FALSE))
        SimulatorQuestionBank.questions.forEach { question ->
            assertFalse(question.options.isEmpty())
            assertFalse(question.correctAnswers.isEmpty())
            assertTrue(question.correctAnswers.all { it in question.options.indices })
        }
    }

    @Test
    fun questionLookupWrapsAcrossTheBank() {
        assertEquals(
            SimulatorQuestionBank.questionAt(0),
            SimulatorQuestionBank.questionAt(SimulatorQuestionBank.questions.size),
        )
    }

    @Test
    fun generatedQuestionsHaveStableSequentialIds() {
        assertEquals((1..100).toList(), SimulatorQuestionBank.questions.map { it.id })
    }

    @Test
    fun sessionRandomlySamplesOneHundredQuestionsWithoutDuplicates() {
        val source = (1..150).map { id ->
            SimulatorQuestion(
                id = id,
                type = SimulatorQuestionType.SINGLE,
                prompt = "题目$id",
                options = listOf("A", "B"),
                correctAnswers = setOf(0),
            )
        }

        val first = SimulatorQuestionBank.randomSession(source, 100, seed = 1234L)
        val sameSeed = SimulatorQuestionBank.randomSession(source, 100, seed = 1234L)
        val differentSeed = SimulatorQuestionBank.randomSession(source, 100, seed = 5678L)

        assertEquals(100, first.size)
        assertEquals(100, first.map { it.id }.toSet().size)
        assertEquals(first.map { it.id }, sameSeed.map { it.id })
        assertFalse(first.map { it.id } == differentSeed.map { it.id })
    }
}
