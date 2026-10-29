package com.futureyyy.tijing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizModelParserTest {
    @Test
    fun parsesRuntimeConfigWithDefaults() {
        val config = QuizRuntimeConfig.fromJson(
            """
            {
              "endpoint": "https://example.test/v1",
              "model": "vision-model",
              "apiKey": "secret-key",
              "prompt": "Read the question",
              "mode": "auto",
              "timeoutMs": 5000,
              "retryCount": 5
            }
            """.trimIndent(),
        )

        assertEquals("https://example.test/v1", config.endpoint)
        assertEquals("vision-model", config.model)
        assertEquals("secret-key", config.apiKey)
        assertEquals("Read the question", config.prompt)
        assertEquals(QuizMode.AUTO, config.mode)
        assertEquals(QuizTargetApp.AUTO, config.targetApp)
        assertEquals(5000, config.timeoutMs)
        assertEquals(5, config.retryCount)
    }

    @Test
    fun replacesBlankRuntimePromptWithStructuredDefault() {
        val config = QuizRuntimeConfig.fromJson(
            """
            {
              "endpoint": "https://example.test/v1",
              "model": "vision-model",
              "apiKey": "secret-key",
              "prompt": "   ",
              "mode": "auto"
            }
            """.trimIndent(),
        )

        assertTrue(config.prompt.isNotBlank())
        assertTrue(config.prompt.contains("\"options\""))
        assertTrue(config.prompt.contains("\"answer_indices\""))
        assertTrue(config.prompt.contains("\"confirm_box\""))
    }

    @Test
    fun parsesAutomaticAndSimulatorTargetsAndRejectsUnknownTarget() {
        val base = """
            {
              "endpoint": "https://example.test/v1",
              "model": "vision-model",
              "apiKey": "secret-key",
              "targetApp": "simulator"
            }
        """.trimIndent()

        assertEquals(QuizTargetApp.SIMULATOR, QuizRuntimeConfig.fromJson(base).targetApp)
        assertEquals(QuizTargetApp.AUTO, QuizRuntimeConfig.fromJson(base.replace("simulator", "auto")).targetApp)
        assertThrows(IllegalArgumentException::class.java) {
            QuizRuntimeConfig.fromJson(base.replace("simulator", "unknown"))
        }
    }

    @Test
    fun rejectsInvalidRuntimeConfigFields() {
        val base = """
            {
              "endpoint": "https://example.test/v1",
              "model": "vision-model",
              "apiKey": "secret-key",
              "mode": "manual",
              "timeoutMs": 30000,
              "retryCount": 2
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            QuizRuntimeConfig.fromJson(base.replace("https://example.test/v1", "ftp://example.test"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuizRuntimeConfig.fromJson(base.replace("vision-model", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuizRuntimeConfig.fromJson(base.replace("secret-key", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuizRuntimeConfig.fromJson(base.replace("30000", "4999"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuizRuntimeConfig.fromJson(base.replace("\"retryCount\": 2", "\"retryCount\": 6"))
        }
    }

    @Test
    fun rejectsRuntimeConfigFieldsWithWrongJsonTypes() {
        val base = """
            {
              "endpoint": "https://example.test/v1",
              "model": "vision-model",
              "apiKey": "secret-key",
              "prompt": "Read the question",
              "mode": "manual",
              "timeoutMs": 30000,
              "retryCount": 2
            }
        """.trimIndent()

        listOf(
            base.replace("\"model\": \"vision-model\"", "\"model\": true"),
            base.replace("\"apiKey\": \"secret-key\"", "\"apiKey\": 123"),
            base.replace("\"prompt\": \"Read the question\"", "\"prompt\": false"),
            base.replace("\"mode\": \"manual\"", "\"mode\": 1"),
            base.replace("\"timeoutMs\": 30000", "\"timeoutMs\": \"bad\""),
            base.replace("\"retryCount\": 2", "\"retryCount\": 2.9"),
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                QuizRuntimeConfig.fromJson(raw)
            }
        }
    }

    @Test
    fun rejectsImpreciseIntegersAndInvalidEndpointPorts() {
        val base = """
            {
              "endpoint": "https://example.test/v1",
              "model": "vision-model",
              "apiKey": "secret-key",
              "timeoutMs": 5000,
              "retryCount": 2
            }
        """.trimIndent()

        listOf(
            base.replace("\"timeoutMs\": 5000", "\"timeoutMs\": 5000.0000000000000000001"),
            base.replace("https://example.test/v1", "https://example.test:0/v1"),
            base.replace("https://example.test/v1", "https://example.test:65536/v1"),
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                QuizRuntimeConfig.fromJson(raw)
            }
        }
    }

    @Test
    fun acceptsScientificNotationOnlyForExactIntegers() {
        val exact = QuizRuntimeConfig.fromJson(
            """
            {
              "endpoint": "https://example.test/v1",
              "model": "vision-model",
              "apiKey": "secret-key",
              "timeoutMs": 5e3,
              "retryCount": 2e0
            }
            """.trimIndent(),
        )

        assertEquals(5000, exact.timeoutMs)
        assertEquals(2, exact.retryCount)
        assertThrows(IllegalArgumentException::class.java) {
            QuizRuntimeConfig.fromJson(
                """
                {
                  "endpoint": "https://example.test/v1",
                  "model": "vision-model",
                  "apiKey": "secret-key",
                  "timeoutMs": 5.0001e3,
                  "retryCount": 2
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun validatesNormalizedBoxAndCalculatesCenter() {
        val box = NormalizedBox(x = 8f, y = 43f, width = 84f, height = 8f)

        assertEquals(50f, box.centerX)
        assertEquals(47f, box.centerY)
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedBox(x = 90f, y = 10f, width = 11f, height = 10f)
        }
    }

    @Test
    fun parsesSingleQuestionWithNormalizedBox() {
        val result = QuizModelParser.parse(
            """
            ```json
            {
              "question_no": 7,
              "question_type": "single",
              "question": "Which option?",
              "options": [
                {"index": 7, "text": "A", "box": [8, 43, 84, 8]},
                {"index": 3, "text": "B", "box": [8, 53, 84, 8]}
              ],
              "answer_indices": [3],
              "confirm_box": [70, 88, 22, 8],
              "next_box": null,
              "confidence": 0.95
            }
            ```
            """.trimIndent(),
        )

        assertEquals(7, result.questionNo)
        assertEquals(QuestionType.SINGLE, result.type)
        assertEquals(listOf(3), result.answerIndices)
        assertEquals(50f, result.options[1].box?.centerX)
        assertEquals(57f, result.options[1].box?.centerY)
        assertEquals(NormalizedBox(70f, 88f, 22f, 8f), result.confirmBox)
        assertEquals(0.95, result.confidence, 0.0)
    }

    @Test
    fun parsesMultipleQuestionConfirmBoxAndLegacyNextBox() {
        val result = QuizModelParser.parse(multipleQuestionJson())

        assertEquals(QuestionType.MULTIPLE, result.type)
        assertEquals(listOf(7, 3), result.answerIndices)
        assertEquals(NormalizedBox(62f, 86f, 30f, 9f), result.confirmBox)
        assertEquals(NormalizedBox(70f, 88f, 22f, 8f), result.nextBox)
    }

    @Test
    fun parsesTrueFalseQuestionWithOneAnswer() {
        val result = QuizModelParser.parse(
            singleQuestionJson()
                .replace("\"question_type\": \"single\"", "\"question_type\": \"true_false\""),
        )

        assertEquals(QuestionType.TRUE_FALSE, result.type)
        assertEquals(listOf(3), result.answerIndices)
    }

    @Test
    fun parsesIndefiniteQuestionWithMultipleAnswers() {
        val result = QuizModelParser.parse(
            singleQuestionJson()
                .replace("\"question_type\": \"single\"", "\"question_type\": \"indefinite\"")
                .replace("\"answer_indices\": [3]", "\"answer_indices\": [3, 7]"),
        )

        assertEquals(QuestionType.INDEFINITE, result.type)
        assertEquals(listOf(3, 7), result.answerIndices)
    }

    @Test
    fun rejectsMissingAnswerIndices() {
        val raw = singleQuestionJson().replace("\"answer_indices\": [3],", "")

        val error = assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.parse(raw)
        }

        assertTrue(error.message.orEmpty().contains("answer_indices"))
    }

    @Test
    fun rejectsAnswerIndexOutsideOptions() {
        val raw = singleQuestionJson().replace("\"answer_indices\": [3]", "\"answer_indices\": [99]")

        val error = assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.parse(raw)
        }

        assertTrue(error.message.orEmpty().contains("answer_indices"))
    }

    @Test
    fun rejectsDuplicateAnswerIndices() {
        val raw = multipleQuestionJson().replace("\"answer_indices\": [7, 3]", "\"answer_indices\": [7, 7]")

        val error = assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.parse(raw)
        }

        assertTrue(error.message.orEmpty().contains("重复"))
    }

    @Test
    fun rejectsSingleQuestionWithMultipleAnswers() {
        val raw = singleQuestionJson().replace("\"answer_indices\": [3]", "\"answer_indices\": [7, 3]")

        val error = assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.parse(raw)
        }

        assertTrue(error.message.orEmpty().contains("只有一个答案"))
    }

    @Test
    fun rejectsInvalidBoxAndConfidence() {
        assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.parse(singleQuestionJson().replace("[8, 43, 84, 8]", "[90, 43, 84, 8]"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.parse(singleQuestionJson().replace("\"confidence\": 0.95", "\"confidence\": 1.1"))
        }
    }

    @Test
    fun rejectsCoordinatesThatOnlyLookValidAfterFloatingPointRounding() {
        listOf(
            singleQuestionJson().replace("[8, 43, 84, 8]", "[-0.00000000000000000001, 43, 84, 8]"),
            singleQuestionJson().replace("[8, 43, 84, 8]", "[8, 43, 92.00000000000000000001, 8]"),
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                QuizModelParser.parse(raw)
            }
        }
    }

    @Test
    fun rejectsOptionAndAnswerIndicesWithHiddenFractionalParts() {
        assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.parse(
                singleQuestionJson().replace("\"index\": 3", "\"index\": 3.00000000000000000001"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.parse(
                singleQuestionJson().replace("\"answer_indices\": [3]", "\"answer_indices\": [3.00000000000000000001]"),
            )
        }
    }

    @Test
    fun rejectsQuestionAndOptionTextWithWrongJsonTypes() {
        listOf(
            singleQuestionJson().replace("\"question\": \"Which option?\"", "\"question\": false"),
            singleQuestionJson().replace("\"text\": \"A\"", "\"text\": 123"),
            singleQuestionJson().replace("\"question\": \"Which option?\"", "\"question\": \"   \""),
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                QuizModelParser.parse(raw)
            }
        }
    }

    @Test
    fun actionPlanLooksUpAnswersByOptionIndex() {
        val result = QuizModelParser.parse(multipleQuestionJson())

        val plan = QuizModelParser.actionPlan(result, questionNo = 99)

        assertEquals(listOf(7, 3), plan.answerOptions.map { it.index })
        assertEquals(listOf("A", "B"), plan.answerOptions.map { it.text })
        assertEquals(NormalizedBox(62f, 86f, 30f, 9f), plan.confirmBox)
    }

    @Test
    fun keepsConfirmButtonBeyondQuestionOneHundredInActionPlan() {
        val result = QuizModelParser.parse(multipleQuestionJson())

        val plan = QuizModelParser.actionPlan(result, questionNo = 1_001)

        assertEquals(NormalizedBox(62f, 86f, 30f, 9f), plan.confirmBox)
    }

    @Test
    fun allowsMissingConfirmBoxForAccessibilityTextFallback() {
        val result = QuizModelParser.parse(
            singleQuestionJson().replace("\"confirm_box\": [70, 88, 22, 8]", "\"confirm_box\": null"),
        )

        val plan = QuizModelParser.actionPlan(result, questionNo = 7)

        assertEquals(null, plan.confirmBox)
    }

    @Test
    fun rejectsActionPlanWhenAnswerOptionHasNoBox() {
        val result = QuizModelParser.parse(
            singleQuestionJson()
                .replace("{\"index\": 3, \"text\": \"B\", \"box\": [8, 53, 84, 8]}", "{\"index\": 3, \"text\": \"B\", \"box\": null}"),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            QuizModelParser.actionPlan(result, questionNo = 7)
        }

        assertTrue(error.message.orEmpty().contains("box"))
    }

    private fun singleQuestionJson() = """
        {
          "question_no": 7,
          "question_type": "single",
          "question": "Which option?",
          "options": [
            {"index": 7, "text": "A", "box": [8, 43, 84, 8]},
            {"index": 3, "text": "B", "box": [8, 53, 84, 8]}
          ],
          "answer_indices": [3],
          "confirm_box": [70, 88, 22, 8],
          "next_box": null,
          "confidence": 0.95
        }
    """.trimIndent()

    private fun multipleQuestionJson() = """
        {
          "question_no": 99,
          "question_type": "multiple",
          "question": "Choose all",
          "options": [
            {"index": 7, "text": "A", "box": [8, 43, 84, 8]},
            {"index": 3, "text": "B", "box": [8, 53, 84, 8]},
            {"index": 9, "text": "C", "box": null}
          ],
          "answer_indices": [7, 3],
          "confirm_box": [62, 86, 30, 9],
          "next_box": [70, 88, 22, 8],
          "confidence": 0.87
        }
    """.trimIndent()
}
