package com.botlisa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the false-positive found live: saying "лиса, что ещё?" (next
 * suggestion) fired the translate trigger instead, because every default
 * command now opens with the same "лиса"/"Lisa" wake word (see
 * TriggerPhraseConfig) and SpeechAssistant's partial fast-path used
 * matchesPrefix() -- which exact-matches on a shared prefix regardless of
 * what follows it -- without first checking how much of that prefix is
 * actually shared. commonPrefixLength() is what SpeechAssistant now gates
 * that fast path on.
 */
class TriggerPhraseCommonPrefixTest {

    @Test
    fun `shared wake word is detected as the common prefix`() {
        val phrases = listOf("лиса, как сказать?", "лиса, что это значит?", "лиса, что ещё?", "лиса, как ответить?")
        // "лиса " (5 chars) is shared by all four; they diverge right after.
        assertEquals(5, TriggerPhraseDetector.commonPrefixLength(phrases))
    }

    @Test
    fun `a partial still inside the shared wake word doesn't clear it`() {
        val commonPrefixLength = TriggerPhraseDetector.commonPrefixLength(
            listOf("лиса, как сказать?", "лиса, что это значит?", "лиса, что ещё?", "лиса, как ответить?"),
        )
        assertFalse(TriggerPhraseDetector.normalize("лиса").length > commonPrefixLength)
        assertFalse(TriggerPhraseDetector.normalize("лиса ").length > commonPrefixLength)
    }

    @Test
    fun `a partial past the wake word clears it`() {
        val commonPrefixLength = TriggerPhraseDetector.commonPrefixLength(
            listOf("лиса, как сказать?", "лиса, что это значит?", "лиса, что ещё?", "лиса, как ответить?"),
        )
        assertTrue(TriggerPhraseDetector.normalize("лиса что").length > commonPrefixLength)
    }

    @Test
    fun `no shared prefix when phrases diverge immediately`() {
        assertEquals(0, TriggerPhraseDetector.commonPrefixLength(listOf("how to say?", "what else?")))
    }

    @Test
    fun `fewer than two phrases has no meaningful common prefix`() {
        assertEquals(0, TriggerPhraseDetector.commonPrefixLength(listOf("лиса, как сказать?")))
        assertEquals(0, TriggerPhraseDetector.commonPrefixLength(emptyList()))
    }
}
