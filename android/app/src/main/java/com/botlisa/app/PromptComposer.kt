package com.botlisa.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin port of llm_lab/prompts/compose_prompt.py, scoped down to exactly
 * what "что ещё" + the caregiver_infant persona actually need: the
 * finalized what_else.json user_template only references {utterance} (no
 * {activity} -- dropped upstream since production has no routine
 * classifier), and caregiver_infant's examples file only uses
 * few_shot_examples (no bad_example/good_examples branch). The `generic`
 * flag and eval-case-file loading from the Python original aren't ported --
 * neither is exercised by this persona/task combo. See
 * ON_DEVICE_LLM_PLAN.md Phase 3.
 *
 * Source JSON lives under assets/llm_prompts/, hand-copied from
 * llm_lab/prompts/ -- not build-linked, a known drift risk accepted for now.
 */
object PromptComposer {
    data class Prompt(val system: String, val user: String)

    private const val TASK_PATH = "llm_prompts/what_else.json"
    private const val PERSONA_PATH = "llm_prompts/personas/caregiver_infant.json"

    // One file, one block per language code in SupportedLanguages.ALL. Each
    // block has the same shape the old single-language example files used --
    // a `language_display` string plus exactly two `few_shot_examples` demos
    // -- so the composition below is unchanged; only which block gets picked
    // is new. Hand-copied from llm_lab/prompts/examples/, same known drift
    // risk as the other assets here.
    private const val EXAMPLES_BY_LANGUAGE_PATH =
        "llm_prompts/examples/few_shot_examples.caregiver_infant.by_language.json"

    // The block every unknown language falls back to. ru-RU is the only
    // native-tuned, battle-tested one (it matches
    // what_else.ru.caregiver_infant.json and the Python reference); the rest
    // are machine translations of the same two scenarios, good enough for a
    // working default a user can later improve.
    private const val FALLBACK_LANGUAGE_CODE = "ru-RU"

    // Both braces escaped -- desktop java.util.regex tolerates a bare "}",
    // but Android's ICU-backed Pattern implementation rejects it as a
    // syntax error at runtime. The JVM unit test alone wouldn't have caught
    // this; only running on-device did. See ON_DEVICE_LLM_PLAN.md Phase 4.
    private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

    fun compose(context: Context, utterance: String, languageCode: String): Prompt {
        val template = loadJson(context, TASK_PATH)
        val persona = loadJson(context, PERSONA_PATH)
        val examples = resolveExamplesForLanguage(loadJson(context, EXAMPLES_BY_LANGUAGE_PATH), languageCode)
        // A user-edited set of demos (see FewShotExamplesConfig.kt / the
        // Settings screen) takes the place of the language's default demos;
        // language_display still comes from the asset default since the
        // override only ever replaces the demos, not the language name.
        val overrideDemos = FewShotExamplesConfig.getOverride(context, languageCode)
        val resolvedExamples = if (overrideDemos != null) {
            JSONObject(examples.toString()).put("few_shot_examples", overrideDemos)
        } else {
            examples
        }
        val gender = GenderConfig.getGender(context).promptValue
        return compose(template, persona, resolvedExamples, utterance, gender)
    }

    /** The demos currently in effect for [languageCode] -- a user override if
     * one has been saved, otherwise the bundled asset default. Used by the
     * Settings screen's few-shot editor to know what to show/edit. */
    fun currentExamplesForLanguage(context: Context, languageCode: String): JSONArray {
        val override = FewShotExamplesConfig.getOverride(context, languageCode)
        if (override != null) return override
        return defaultExamplesForLanguage(context, languageCode)
    }

    /** The bundled asset default demos for [languageCode], ignoring any user
     * override -- what the Settings screen's "reset to default" reverts to. */
    fun defaultExamplesForLanguage(context: Context, languageCode: String): JSONArray {
        val byLanguage = loadJson(context, EXAMPLES_BY_LANGUAGE_PATH)
        return resolveExamplesForLanguage(byLanguage, languageCode).getJSONArray("few_shot_examples")
    }

    /**
     * Picks the few-shot block for [languageCode] out of the by-language
     * examples file, falling back to [FALLBACK_LANGUAGE_CODE] when that
     * language has no block yet (or the code just doesn't match). The
     * returned object has the same `language_display` + `few_shot_examples`
     * shape a single-language file had, so [compose] below doesn't care
     * which path produced it.
     */
    fun resolveExamplesForLanguage(byLanguage: JSONObject, languageCode: String): JSONObject =
        byLanguage.optJSONObject(languageCode) ?: byLanguage.getJSONObject(FALLBACK_LANGUAGE_CODE)

    /**
     * The actual composition logic, Context-free -- takes already-parsed
     * JSON directly so it's testable on a plain JVM (no Robolectric/Mockito
     * needed just to fake an AssetManager).
     */
    fun compose(
        template: JSONObject,
        persona: JSONObject,
        examples: JSONObject,
        utterance: String,
        gender: String = persona.getString("gender"),
    ): Prompt {
        val fields = mapOf(
            "gender" to gender,
            "language" to examples.getString("language_display"),
            "speaker" to persona.getString("default_speaker"),
            "few_shot_examples" to formatFewShotExamples(examples.getJSONArray("few_shot_examples")),
            "utterance" to utterance,
        )

        val system = substitute(render(persona.get("system_template")), fields)
        val user = substitute(render(template.get("user_template")), fields)
        return Prompt(system, user)
    }

    private fun loadJson(context: Context, assetPath: String): JSONObject {
        val text = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }

    private fun formatFewShotExamples(demos: JSONArray): String {
        val blocks = (0 until demos.length()).map { i ->
            val demo = demos.getJSONObject(i)
            val responses = demo.getJSONArray("responses")
            val lines = mutableListOf("Heard: \"${demo.getString("heard")}\"")
            lines += (0 until responses.length()).map { responses.getString(it) }
            lines.joinToString("\n")
        }
        return blocks.joinToString("\n\n")
    }

    /**
     * Reassembles a template field stored as a JSON array of lines (so the
     * asset JSON stays readable/diffable, matching compose_prompt.py's own
     * _render) back into one string, lines joined by "\n". Each line may
     * itself be a nested array of word-wrap fragments (joined by a space).
     * A plain string passes through unchanged.
     */
    private fun render(value: Any): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("\n") { renderLine(value.get(it)) }
        else -> throw IllegalArgumentException("Unexpected template value: $value")
    }

    private fun renderLine(value: Any): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString(" ") { value.getString(it) }
        else -> throw IllegalArgumentException("Unexpected template line: $value")
    }

    /** Kotlin has no str.format(**dict) equivalent; the templates only use
     * simple named {placeholders}, so a regex substitution is enough. */
    private fun substitute(template: String, fields: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            fields[key] ?: throw IllegalArgumentException("Missing field for placeholder {$key}")
        }
}
