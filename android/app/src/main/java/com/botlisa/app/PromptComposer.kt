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
    private const val EXAMPLES_PATH = "llm_prompts/examples/what_else.ru.caregiver_infant.json"

    private val PLACEHOLDER = Regex("\\{(\\w+)}")

    fun compose(context: Context, utterance: String): Prompt {
        val template = loadJson(context, TASK_PATH)
        val persona = loadJson(context, PERSONA_PATH)
        val examples = loadJson(context, EXAMPLES_PATH)
        return compose(template, persona, examples, utterance)
    }

    /**
     * The actual composition logic, Context-free -- takes already-parsed
     * JSON directly so it's testable on a plain JVM (no Robolectric/Mockito
     * needed just to fake an AssetManager).
     */
    fun compose(template: JSONObject, persona: JSONObject, examples: JSONObject, utterance: String): Prompt {
        val fields = mapOf(
            "gender" to persona.getString("gender"),
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
