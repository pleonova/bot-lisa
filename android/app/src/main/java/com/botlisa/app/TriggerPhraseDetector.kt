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
        stripDiacritics(text.lowercase()).trim().filter { it.isLetterOrDigit() || it.isWhitespace() }

    // ASR is inconsistent about accents -- "qué más" can come back as "que
    // mas", or a trigger phrase may be typed without them -- so a match
    // can't hinge on diacritics. NFD splits an accented letter into its
    // base + a combining mark (Unicode category Mn); dropping those marks
    // leaves the base letter. A no-op for CJK triggers; for Russian it also
    // folds "ё" -> "е", which ASR tends to do anyway.
    private fun stripDiacritics(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

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

    /**
     * True if [partial] -- an in-progress, still-growing transcript --
     * already looks like the start of [phrase], tolerant of minor ASR
     * mistakes the same way [matches] is. Unlike [matches], which compares
     * the two full strings and so effectively needs [phrase] spoken almost
     * in its entirety before it can clear [threshold], this compares
     * [partial] against [phrase]'s own leading substring of the same
     * length -- so it can fire while the caregiver is still mid-phrase,
     * which is the whole point of checking on partials (see
     * SpeechAssistant.kt's onPartialResults) rather than waiting for a
     * final result.
     *
     * Requires at least a third of [phrase] (3 characters minimum) to
     * already be present before attempting a fuzzy comparison at all -- a
     * handful of characters fuzzy-matches almost anything, which would fire
     * the trigger on unrelated speech that happens to start similarly.
     */
    fun matchesPrefix(partial: String, phrase: String, threshold: Double = 0.75): Boolean {
        if (phrase.isBlank() || partial.isBlank()) return false

        val normalizedPartial = normalize(partial)
        val normalizedPhrase = normalize(phrase)
        if (normalizedPhrase.isBlank()) return false

        // The partial has caught up to (or passed) the whole phrase's
        // length -- matches() already covers this (including a trailing
        // word stuck to the end), no need for prefix logic.
        if (normalizedPartial.length >= normalizedPhrase.length) {
            return matches(partial, phrase, threshold)
        }

        val minPartialLength = (normalizedPhrase.length / 3).coerceAtLeast(3)
        if (normalizedPartial.length < minPartialLength) return false

        val phrasePrefix = normalizedPhrase.substring(0, normalizedPartial.length)
        if (normalizedPartial == phrasePrefix) return true
        return similarityRatio(normalizedPartial, phrasePrefix) >= threshold
    }

    /**
     * Length (in normalized characters) of the longest prefix shared by
     * every phrase in [phrases]. Every default trigger phrase now opens with
     * a common "Lisa"/"лиса" wake word (see TriggerPhraseConfig), which
     * defeats [matchesPrefix]'s own equal-length-prefix comparison for a
     * short partial still inside that shared wake word -- it can (and does)
     * exact-match the wrong command's prefix, since the first several
     * characters of every command are identical. Callers doing a partial
     * fast-path match across several candidate phrases (see
     * SpeechAssistant's translate-trigger fast path) should require the
     * partial to have grown past this length before trusting a match at
     * all, so it's actually looking at each phrase's own distinguishing
     * text rather than the wake word both share.
     */
    fun commonPrefixLength(phrases: List<String>): Int {
        val normalized = phrases.map(::normalize).filter { it.isNotEmpty() }
        if (normalized.size < 2) return 0
        val shortest = normalized.minOf { it.length }
        var i = 0
        while (i < shortest && normalized.all { it[i] == normalized[0][i] }) {
            i++
        }
        return i
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
