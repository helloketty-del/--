package com.futureyyy.tijing

/**
 * Keeps question-bank recognition independent from the visual-answer schema.
 * A model may accidentally include legacy answer fields; bank mode reads only
 * the extracted stem and creates the local result contract itself.
 */
internal object QuestionBankResponseAdapter {
    fun shouldUseVisionFallback(status: QuestionBankMatchStatus): Boolean = when (status) {
        QuestionBankMatchStatus.EXACT,
        QuestionBankMatchStatus.KEY_PHRASE,
        -> false
        QuestionBankMatchStatus.AMBIGUOUS,
        QuestionBankMatchStatus.CONFLICT,
        QuestionBankMatchStatus.NOT_FOUND,
        -> true
    }

    fun matchStatus(raw: String, index: QuestionBankIndex): QuestionBankMatchStatus {
        val stem = QuestionStemParser.parse(raw)
        return index.match(stem).status
    }

    fun toModelJson(raw: String, index: QuestionBankIndex, questionNo: Int): String {
        val stem = QuestionStemParser.parse(raw)
        val match = index.match(stem)
        return index.toModelJson(match, stem, questionNo)
    }
}
