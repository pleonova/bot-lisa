package com.botlisa.app

/**
 * Kotlin port of speech_lab/trigger_phrase.py's fuzzy trigger-phrase match
 * (see that file's docstring in the speech_lab prototype for the full "why
 * not a classifier" reasoning). Same idea, ported so the production app
 * doesn't need an external model dependency: Android's built-in
 * SpeechRecognizer does the transcription (see SpeechAssistant.kt), and
 * this just decides whether what it heard was the configured trigger
 * phrase (see TriggerPhraseConfig.kt) or an ordinary utterance.
 *
 * Kotlin/the JVM stdlib has no equivalent of Python's
 * difflib.SequenceMatcher.ratio(), so this uses a Levenshtein-edit-distance
 * ratio instead: (maxLen - editDistance) / maxLen. Same rough shape (1.0
 * for identical strings, lower for more different ones) but not the same
 * algorithm as the Python prototype, so the numbers won't match exactly --
 * the default threshold below was picked empirically against *this*
 * implementation, not carried over from Python's.
 */
object TriggerPhraseDetector {

    fun normalize(text: String): String =
        text.lowercase().trim().filter { it.isLetterOrDigit() || it.isWhitespace() }

    /**
     * True if [transcript] is (a fuzzy match for) [phrase], tolerant of
     * minor ASR mistakes -- e.g. "как сказать" coming back as "как сказат"
     * or with a trailing word stuck to it. False if [phrase] is blank
     * (nothing configured, or the user cleared the setting).
     */
    fun matches(transcript: String, phrase: String, threshold: Double = 0.75): Boolean {
        if (phrase.isBlank() || transcript.isBlank()) return false

        val normalizedTranscript = normalize(transcript)
        val normalizedPhrase = normalize(phrase)
        if (normalizedPhrase.isBlank()) return false

        // Cheap exact-substring check covers the common case (trigger
        // phrase transcribed correctly, possibly with something else
        // attached); fall back to a similarity ratio for near-misses on
        // the whole transcript.
        if (normalizedTranscript.contains(normalizedPhrase)) return true

        return similarityRatio(normalizedTranscript, normalizedPhrase) >= threshold
    }

    private fun similarityRatio(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        val distance = levenshteinDistance(a, b)
        return (maxLen - distance).toDouble() / maxLen
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previousRow = IntArray(b.length + 1) { it }
        var currentRow = IntArray(b.length + 1)

        for (i in 1..a.length) {
            currentRow[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                currentRow[j] = minOf(
                    currentRow[j - 1] + 1, // insertion
                    previousRow[j] + 1, // deletion
                    previousRow[j - 1] + cost, // substitution
                )
            }
            val temp = previousRow
            previousRow = currentRow
            currentRow = temp
        }
        return previousRow[b.length]
    }
}
