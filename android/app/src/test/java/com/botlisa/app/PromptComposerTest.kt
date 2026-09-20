package com.botlisa.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Verifies PromptComposer's Kotlin port matches llm_lab/prompts/compose_prompt.py's
 * Python reference exactly, including whitespace and line joins -- see
 * ON_DEVICE_LLM_PLAN.md Phase 3's checkpoint.
 *
 * Reads the same assets/llm_prompts/ JSON files the app ships (not a
 * fixture copy), so this test also catches drift if those hand-synced
 * copies of llm_lab/prompts/ ever go stale relative to PromptComposer's
 * expectations.
 */
class PromptComposerTest {

    private fun loadAsset(relativePath: String): JSONObject {
        // src/test/ runs on the JVM directly against the module's file
        // tree -- no AssetManager, so just read the same files by path.
        val file = File("src/main/assets/llm_prompts/$relativePath")
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `composed prompt matches the Python reference exactly`() {
        val template = loadAsset("what_else.json")
        val persona = loadAsset("personas/caregiver_infant.json")
        val examples = loadAsset("examples/what_else.ru.caregiver_infant.json")

        val prompt = PromptComposer.compose(template, persona, examples, "спокойной ночи")

        // Captured from: python3 -c "... compose_prompt(demo_case) ..." with
        // the same case (caregiver_infant, ru, utterance="спокойной ночи").
        val expectedSystem = "You are helping the caregiver who is talking to a small boy.\n" +
            "Everything you produce is in Russian and must be grammatically correct.\n" +
            "Keep it warm and infant-directed: use diminutives/softened forms naturally, simple vocabulary, sentences under 8 words.\n" +
            "Use 'your', not 'my', when referring to the child's things.\n" +
            "Stay closely tied to what was just heard, but don't repeat the same thing; don't add unrelated objects.\n" +
            "Here are examples of the style and format expected:\n" +
            "Heard: \"Давай наденем твою пижамку.\"\n" +
            "Подними ручки.\n" +
            "Просунь ручку в рукавчик.\n" +
            "Какая мягкая пижамка у тебя!\n" +
            "\n" +
            "Heard: \"Давай почитаем твою любимую книжку.\"\n" +
            "Какую книжку ты хочешь почитать?\n" +
            "Какая интересная сказка!\n" +
            "Посмотри на картинку, малыш.\n" +
            "Reply with the phrases only — one per line, no numbering, no preamble."
        val expectedUser = "Give me three follows to this: спокойной ночи"

        assertEquals(expectedSystem, prompt.system)
        assertEquals(expectedUser, prompt.user)
    }

    @Test
    fun `by-language file's ru-RU block matches the standalone Russian file`() {
        val template = loadAsset("what_else.json")
        val persona = loadAsset("personas/caregiver_infant.json")
        val standalone = loadAsset("examples/what_else.ru.caregiver_infant.json")
        val byLanguage = loadAsset("examples/few_shot_examples.caregiver_infant.by_language.json")

        val fromStandalone = PromptComposer.compose(template, persona, standalone, "спокойной ночи")
        val fromByLanguage = PromptComposer.compose(
            template, persona,
            PromptComposer.resolveExamplesForLanguage(byLanguage, "ru-RU"),
            "спокойной ночи",
        )

        assertEquals(fromStandalone.system, fromByLanguage.system)
        assertEquals(fromStandalone.user, fromByLanguage.user)
    }

    @Test
    fun `a non-Russian language composes in that language`() {
        val template = loadAsset("what_else.json")
        val persona = loadAsset("personas/caregiver_infant.json")
        val byLanguage = loadAsset("examples/few_shot_examples.caregiver_infant.by_language.json")

        val prompt = PromptComposer.compose(
            template, persona,
            PromptComposer.resolveExamplesForLanguage(byLanguage, "es-ES"),
            "buenas noches",
        )

        assert(prompt.system.contains("Everything you produce is in Spanish")) { prompt.system }
        assert(prompt.system.contains("Vamos a ponerte el pijama.")) { prompt.system }
        assertEquals("Give me three follows to this: buenas noches", prompt.user)
    }

    @Test
    fun `an unknown language code falls back to the Russian block`() {
        val byLanguage = loadAsset("examples/few_shot_examples.caregiver_infant.by_language.json")
        val resolved = PromptComposer.resolveExamplesForLanguage(byLanguage, "xx-XX")
        assertEquals("Russian", resolved.getString("language_display"))
    }
}
