package com.botlisa.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the false-positive found live: saying "лиса, что ещё?"
 * (next-suggestion) fired the translate trigger ("лиса, как сказать?")
 * instead. commonPrefixLength() (see TriggerPhraseCommonPrefixTest) already
 * stops the fast path from firing on a partial still inside the shared
 * "лиса " wake word, but just past it a short, still-growing remainder can
 * still fuzzy-score >= 0.5 against translate's own prefix by chance -- e.g.
 * "что" against "как" -- even though a sibling trigger is the far better
 * fit. prefixSimilarity() is what SpeechAssistant's fast path now compares
 * across all four candidates to catch that.
 */
class TriggerPhrasePrefixSimilarityTest {

    private val translate = "лиса, как сказать?"
    private val nextSuggestion = "лиса, что ещё?"

    @Test
    fun `a partial just past the shared wake word scores higher against its own command than translate`() {
        // "лиса что" -- 8 normalized chars, just past commonPrefixLength's
        // shared "лиса " (5 chars) -- is where the old flat 0.5 threshold
        // against translate alone used to misfire.
        val partial = "лиса что"
        val translateScore = TriggerPhraseDetector.prefixSimilarity(partial, translate)
        val nextSuggestionScore = TriggerPhraseDetector.prefixSimilarity(partial, nextSuggestion)
        assertTrue(
            "expected next-suggestion ($nextSuggestionScore) to score higher than translate ($translateScore)",
            nextSuggestionScore > translateScore,
        )
    }

    @Test
    fun `a partial that actually is the translate trigger still scores highest against translate`() {
        val partial = "лиса как ска"
        val translateScore = TriggerPhraseDetector.prefixSimilarity(partial, translate)
        val nextSuggestionScore = TriggerPhraseDetector.prefixSimilarity(partial, nextSuggestion)
        assertTrue(
            "expected translate ($translateScore) to score higher than next-suggestion ($nextSuggestionScore)",
            translateScore > nextSuggestionScore,
        )
    }
}
