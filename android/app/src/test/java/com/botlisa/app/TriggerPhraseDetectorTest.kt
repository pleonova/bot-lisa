package com.botlisa.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerPhraseDetectorTest {

    private val phrase = "как сказать"

    @Test
    fun `full phrase matches`() {
        assertTrue(TriggerPhraseDetector.matchesPrefix("как сказать", phrase))
    }

    @Test
    fun `a partial covering most of the phrase matches`() {
        // Missing only the trailing "ть" -- should already clear the bar,
        // which is the whole point: fire before the phrase is complete.
        assertTrue(TriggerPhraseDetector.matchesPrefix("как сказа", phrase, threshold = 0.6))
    }

    @Test
    fun `an exact-prefix partial half the phrase's length matches`() {
        assertTrue(TriggerPhraseDetector.matchesPrefix("как ска", phrase, threshold = 0.6))
    }

    @Test
    fun `a too-short partial never matches even if it's an exact prefix`() {
        // 2 chars is well under the half-phrase floor -- "ка" fuzzy-matches
        // the start of nearly anything, so it must not fire on its own.
        assertFalse(TriggerPhraseDetector.matchesPrefix("ка", phrase, threshold = 0.6))
    }

    @Test
    fun `unrelated speech of similar length does not match`() {
        assertFalse(TriggerPhraseDetector.matchesPrefix("давай почитаем", phrase, threshold = 0.6))
    }

    @Test
    fun `a partial already as long as the whole phrase falls back to whole-string matching`() {
        assertTrue(TriggerPhraseDetector.matchesPrefix("как сказать пожалуйста", phrase))
        assertFalse(TriggerPhraseDetector.matchesPrefix("совсем другое предложение", phrase))
    }

    @Test
    fun `blank inputs never match`() {
        assertFalse(TriggerPhraseDetector.matchesPrefix("", phrase))
        assertFalse(TriggerPhraseDetector.matchesPrefix("как ска", ""))
    }
}
