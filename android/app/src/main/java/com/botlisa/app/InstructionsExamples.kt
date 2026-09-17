package com.botlisa.app

import android.content.Context
import org.json.JSONObject

/**
 * Illustrative per-language examples for the "Use voice commands" panel
 * (InstructionsPanel.kt) -- not used by the real assist flow, just the
 * worked example shown under the translate and next-suggestion cards.
 *
 * [phraseExample] reuses the same caregiver_infant few-shot demos
 * PromptComposer.kt feeds the "what else?" prompt: always demo index 0
 * (the pajamas scenario) from the bundled default, ignoring any user
 * override in Settings -- the hardcoded English gloss below only matches
 * that scenario. [wordExample] has its own small asset file since no
 * per-language word-translation data exists anywhere else in the app.
 */
object InstructionsExamples {
    data class WordExample(val en: String, val translated: String)
    data class PhraseExample(val heard: String, val heardGloss: String, val response: String, val responseGloss: String)

    private const val WORD_EXAMPLE_PATH = "llm_prompts/examples/word_translate_example.by_language.json"
    private const val FALLBACK_LANGUAGE_CODE = "ru-RU"

    // Demo index 0 is the same scripted scenario (putting on pajamas) in
    // every language block -- see word_translate_example.by_language.json's
    // _comment. Its English meaning doesn't vary by language, so it's fixed
    // here instead of translating it back from whichever language is shown.
    private const val PAJAMAS_HEARD_EN = "Let's put on your pajamas."
    private const val PAJAMAS_RESPONSE_EN = "Raise your arms."

    fun wordExample(context: Context, languageCode: String): WordExample {
        val text = context.assets.open(WORD_EXAMPLE_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val byLanguage = JSONObject(text)
        val block = byLanguage.optJSONObject(languageCode) ?: byLanguage.getJSONObject(FALLBACK_LANGUAGE_CODE)
        val example = block.getJSONObject("example")
        return WordExample(example.getString("en"), example.getString("translated"))
    }

    fun phraseExample(context: Context, languageCode: String): PhraseExample {
        val demo = PromptComposer.defaultExamplesForLanguage(context, languageCode).getJSONObject(0)
        return PhraseExample(
            heard = demo.getString("heard"),
            heardGloss = PAJAMAS_HEARD_EN,
            response = demo.getJSONArray("responses").getString(0),
            responseGloss = PAJAMAS_RESPONSE_EN,
        )
    }
}
