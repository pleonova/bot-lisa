package com.botlisa.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The full-screen Settings page -- previously an inline `if (showSettings)`
 * block inside MainActivity.kt's LisaScreen that expanded in place below the
 * main assistant UI. Split out here (a) so it reads as its own page (the
 * caller overlays a fixed [SettingsHeaderBar] on top, rather than pushing it
 * down) and (b) so the settings themselves are grouped under section headers
 * instead of one long undifferentiated list.
 *
 * State that's shared with the main screen (target language, trigger
 * phrases, dark mode, server config) is hoisted by the caller, same as
 * before. Gender and the few-shot example overrides are new, self-contained
 * settings -- read/written directly against GenderConfig.kt /
 * FewShotExamplesConfig.kt here, since nothing outside Settings needs them
 * as live Compose state.
 */
@Composable
fun SettingsScreen(
    isDark: Boolean,
    onToggleDark: () -> Unit,
    targetLanguage: TargetLanguage,
    onTargetLanguageChange: (TargetLanguage) -> Unit,
    translateTriggerPhrase: String,
    onTranslateTriggerPhraseChange: (String) -> Unit,
    meaningTriggerPhrase: String,
    onMeaningTriggerPhraseChange: (String) -> Unit,
    nextSuggestionTriggerPhrase: String,
    onNextSuggestionTriggerPhraseChange: (String) -> Unit,
    nextSuggestionSupported: Boolean,
    answerTriggerPhrase: String,
    onAnswerTriggerPhraseChange: (String) -> Unit,
    curatedRelatedSupported: Boolean,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    // Set when the caregiver got here via the instructions panel's "go to
    // settings" row (see MainActivity's onOpenSettings) rather than the
    // header's own gear icon -- moves "Voice commands" to the very top of
    // this list (see below) and expands it immediately, instead of its
    // usual place further down, collapsed by default. A first version of
    // this tried to leave the section in place and scroll to it instead,
    // computed from raw LayoutCoordinates -- reordering it to the top is far
    // simpler and can't drift out of sync with the actual layout the way
    // that hand-rolled scroll-offset math did (it was landing at the bottom
    // of the page instead of at the section).
    expandVoiceCommandsInitially: Boolean = false,
    pulseEnabled: Boolean = true,
    onPulseEnabledChange: (Boolean) -> Unit = {},
) {
    // LocalContext.current retrieves the Context for use inside a
    // Composable -- Compose functions don't take Context as an ordinary
    // parameter, so this is how they reach it (needed here to read/write
    // SharedPreferences-backed settings like GenderConfig, OnDeviceLlmConfig).
    val context = LocalContext.current

    // No own verticalScroll/fillMaxSize here: this renders inside
    // MainActivity's LisaScreen, whose outer Column is already
    // fillMaxSize().verticalScroll(...) -- nesting a second scrollable
    // Column inside that throws ("measured with an infinity maximum height
    // constraints") the moment this screen becomes visible.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Reserves room for the fixed SettingsHeaderBar the caller overlays
        // on top of this scrolling content -- see that composable's height.
        Spacer(Modifier.height(SETTINGS_HEADER_HEIGHT))

        if (expandVoiceCommandsInitially) {
            VoiceCommandsSection(
                targetLanguage = targetLanguage,
                translateTriggerPhrase = translateTriggerPhrase,
                onTranslateTriggerPhraseChange = onTranslateTriggerPhraseChange,
                meaningTriggerPhrase = meaningTriggerPhrase,
                onMeaningTriggerPhraseChange = onMeaningTriggerPhraseChange,
                nextSuggestionTriggerPhrase = nextSuggestionTriggerPhrase,
                onNextSuggestionTriggerPhraseChange = onNextSuggestionTriggerPhraseChange,
                nextSuggestionSupported = nextSuggestionSupported,
                answerTriggerPhrase = answerTriggerPhrase,
                onAnswerTriggerPhraseChange = onAnswerTriggerPhraseChange,
                curatedRelatedSupported = curatedRelatedSupported,
                initiallyExpanded = true,
            )
        }

        SettingsSection("Language") {
            LanguagePicker(targetLanguage, onTargetLanguageChange)
            // Names the actual source for the CURRENT target language instead
            // of naming Russian outright -- curatedRelatedSupported is only
            // ever true when targetLanguage is Russian (see MainActivity's
            // curatedRelatedSupported comment), so this reads correctly for
            // every language rather than always claiming a Russian library.
            val relatedPhraseNote = when {
                curatedRelatedSupported ->
                    "Related-phrase suggestions come from the curated ${targetLanguage.displayName} library."
                nextSuggestionSupported ->
                    "Related-phrase suggestions for ${targetLanguage.displayName} come from on-device AI generation."
                else ->
                    "Related-phrase suggestions aren't available yet for ${targetLanguage.displayName}."
            }
            Text(
                "What English translates into, the voice that reads it back, and what Lisa Assistant listens for. $relatedPhraseNote",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection("Appearance") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dark mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Overrides the system setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isDark, onCheckedChange = { onToggleDark() })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pulsing record-button text", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The text under the record button animates to draw attention. Turn off to keep it in its solid color instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = pulseEnabled, onCheckedChange = onPulseEnabledChange)
            }
        }

        // Visible only on capable hardware (API 33+, enough RAM) -- hides
        // rather than disables controls on ineligible configurations. See
        // ON_DEVICE_LLM_PLAN.md Phase 5.
        val deviceCapable = OnDeviceLlm.isDeviceCapable(context)

        if (deviceCapable) {
            SettingsSection("\"What else?\" timing") {
                PrefetchModePicker(context)
            }

            // Collapsed by default -- the model download status is
            // set-once-and-forget for most caregivers, unlike suggestion
            // timing above (EAGER/ON_DEMAND), which trades off battery/heat
            // and is more likely to get revisited. No source picker here any
            // more (AI vs. library) -- it always uses AI, falling back to
            // the curated library only where that library has content
            // (Russian) -- see OnDeviceLlmConfig.getWhatElseSource()'s
            // comment for why the picker was removed.
            SettingsSection("\"What else?\" model", initiallyExpanded = false) {
                ModelDownloadStatus(context)
            }
        }

        if (!expandVoiceCommandsInitially) {
            VoiceCommandsSection(
                targetLanguage = targetLanguage,
                translateTriggerPhrase = translateTriggerPhrase,
                onTranslateTriggerPhraseChange = onTranslateTriggerPhraseChange,
                meaningTriggerPhrase = meaningTriggerPhrase,
                onMeaningTriggerPhraseChange = onMeaningTriggerPhraseChange,
                nextSuggestionTriggerPhrase = nextSuggestionTriggerPhrase,
                onNextSuggestionTriggerPhraseChange = onNextSuggestionTriggerPhraseChange,
                nextSuggestionSupported = nextSuggestionSupported,
                answerTriggerPhrase = answerTriggerPhrase,
                onAnswerTriggerPhraseChange = onAnswerTriggerPhraseChange,
                curatedRelatedSupported = curatedRelatedSupported,
                initiallyExpanded = false,
            )
        }

        // Split out from the "What else?" section above and collapsed by
        // default -- gender and the few-shot demos steer prompt wording
        // rather than *whether*/*when* generation runs, and most caregivers
        // never need to touch them once set, so they shouldn't compete for
        // attention with the more commonly-adjusted toggles above.
        if (deviceCapable) {
            SettingsSection("Prompt customization", initiallyExpanded = false) {
                GenderPicker(context)
                Spacer(Modifier.height(4.dp))
                FewShotExamplesEditor(context, targetLanguage)
            }
        }

        SettingsSection("Server connection") {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server URL") },
                supportingText = { Text("Emulator: http://10.0.2.2:8002 · Real device: http://<mac-lan-ip>:8002 · Deployed: http://<load-balancer-ip>:8002") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API key (optional)") },
                supportingText = { Text("Only required when the server enforces ORCHESTRATION_API_KEY, e.g. the deployed cluster.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}

/**
 * The trigger-phrase editors -- pulled out of [SettingsScreen]'s main body so
 * it can render either in its usual spot (collapsed by default, after the
 * "what else?" sections) or promoted to the very top of the list when the
 * caregiver got here via the instructions panel's "go to settings" row (see
 * expandVoiceCommandsInitially), without duplicating these four fields.
 */
@Composable
private fun VoiceCommandsSection(
    targetLanguage: TargetLanguage,
    translateTriggerPhrase: String,
    onTranslateTriggerPhraseChange: (String) -> Unit,
    meaningTriggerPhrase: String,
    onMeaningTriggerPhraseChange: (String) -> Unit,
    nextSuggestionTriggerPhrase: String,
    onNextSuggestionTriggerPhraseChange: (String) -> Unit,
    nextSuggestionSupported: Boolean,
    answerTriggerPhrase: String,
    onAnswerTriggerPhraseChange: (String) -> Unit,
    curatedRelatedSupported: Boolean,
    initiallyExpanded: Boolean,
) {
    SettingsSection("Voice commands", initiallyExpanded = initiallyExpanded) {
        OutlinedTextField(
            value = translateTriggerPhrase,
            onValueChange = onTranslateTriggerPhraseChange,
            label = { Text("Translate") },
            supportingText = { Text("Say this, pause, then an English word, to have Lisa Assistant translate it instead of treating it as ${targetLanguage.displayName}.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = meaningTriggerPhrase,
            onValueChange = onMeaningTriggerPhraseChange,
            label = { Text("What does that mean?") },
            supportingText = { Text("Say this to hear an English translation of the last thing you said, spoken aloud.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (nextSuggestionSupported) {
            OutlinedTextField(
                value = nextSuggestionTriggerPhrase,
                onValueChange = onNextSuggestionTriggerPhraseChange,
                label = { Text("Next suggestion") },
                supportingText = { Text("Say this to have Lisa Assistant read the next suggested phrase aloud. Say it again for the next one in the list.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        if (curatedRelatedSupported) {
            OutlinedTextField(
                value = answerTriggerPhrase,
                onValueChange = onAnswerTriggerPhraseChange,
                label = { Text("How to answer?") },
                supportingText = { Text("Say this to look up phrases you could say back to what you just heard.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

/** Height of [SettingsHeaderBar], reserved as a spacer at the top of
 * [SettingsScreen]'s scrolling content so the fixed header never covers it. */
private val SETTINGS_HEADER_HEIGHT = 64.dp

/**
 * A fixed header bar for the Settings page -- rendered by the caller as a
 * sibling layered on top of [SettingsScreen]'s scrolling content (inside the
 * same Box), rather than as part of that scrolling Column, so "Settings" and
 * the close button stay visible no matter how far the caregiver has scrolled
 * down the settings list. Replaces the old back-arrow-in-the-flow header.
 */
@Composable
fun SettingsHeaderBar(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SETTINGS_HEADER_HEIGHT)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close settings")
            }
        }
    }
}

/**
 * A collapsible group of settings, expanded by default -- tapping the
 * header (title or chevron) toggles it. Collapsed state is local to this
 * composable instance (not persisted), so every section starts open again
 * next time Settings is opened -- simplest behavior and avoids yet another
 * SharedPreferences key for something this low-stakes.
 */
@Composable
private fun SettingsSection(
    title: String,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // remember { mutableStateOf(...) } is how Compose holds mutable state:
    // plain local `var` would reset to its initial value on every
    // recomposition, but wrapping it in `remember` keeps the same value
    // across recompositions and reading/writing it (via `by`) automatically
    // triggers a recomposition so the UI updates.
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        HorizontalDivider()
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/**
 * Material3's ExposedDropdownMenu flips above the field whenever it doesn't
 * measure enough room below (a 48dp margin plus the menu's own height) --
 * capping the height alone doesn't stop that. Anchor a plain Popup to the
 * field's bottom edge instead, which always opens downward regardless of
 * available space.
 */
@Composable
private fun LanguagePicker(targetLanguage: TargetLanguage, onChange: (TargetLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var fieldHeightPx by remember { mutableStateOf(0) }
    var fieldWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Box {
        // supportingText deliberately NOT used here (even though
        // OutlinedTextField has that slot) -- it's measured as part of the
        // field's own onGloballyPositioned height below, which would push
        // the popup's anchor point below the explanation text instead of
        // right under the bordered box. The caller renders that text as a
        // plain Text after this composable instead.
        OutlinedTextField(
            value = targetLanguage.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Target language") },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    fieldHeightPx = it.size.height
                    fieldWidthPx = it.size.width
                },
        )
        // A readOnly OutlinedTextField still consumes taps for its own
        // focus-request behavior, so a .clickable{} modifier on the field
        // itself never fires -- confirmed live on a physical device: tapping
        // focused the field (label/border turned purple) but the dropdown
        // never opened. This transparent overlay sits on top and gets the
        // tap first instead.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, fieldHeightPx),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall,
                    // tonalElevation deliberately 0 -- Material3 tints a
                    // Surface toward the primary color at higher elevations,
                    // which on this app's purple theme showed up as an
                    // unwanted lavender wash behind the list.
                    // shadowElevation alone still gives it a floating drop
                    // shadow.
                    tonalElevation = 0.dp,
                    shadowElevation = 3.dp,
                    modifier = Modifier.width(with(density) { fieldWidthPx.toDp() }),
                ) {
                    val listScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(listScrollState)
                            .languageListScrollbar(listScrollState),
                    ) {
                        SupportedLanguages.ALL.forEach { language ->
                            LanguageDropdownItem(
                                language = language,
                                selected = language.code == targetLanguage.code,
                                onClick = {
                                    onChange(language)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrefetchModePicker(context: Context) {
    var prefetchMode by remember { mutableStateOf(OnDeviceLlmConfig.getPrefetchMode(context)) }
    listOf(
        OnDeviceLlmConfig.PrefetchMode.EAGER to
            ("Eager — generate after every phrase, so \"what else?\" answers instantly" to
                "Uses more battery and can heat up your device faster, since it runs on-device AI whether or not you end up asking. Backs off automatically once your device is already warm."),
        OnDeviceLlmConfig.PrefetchMode.ON_DEMAND to
            ("On-demand — only generate when you say \"what else?\"" to
                "Uses less battery. You'll hear a short pause the first time you ask about each phrase."),
    ).forEach { (mode, labelAndCaption) ->
        val (label, caption) = labelAndCaption
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    prefetchMode = mode
                    OnDeviceLlmConfig.setPrefetchMode(context, mode)
                },
        ) {
            RadioButton(
                selected = prefetchMode == mode,
                onClick = {
                    prefetchMode = mode
                    OnDeviceLlmConfig.setPrefetchMode(context, mode)
                },
            )
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The child's gender, fed into the on-device persona's {gender} placeholder
 * (see GenderConfig.kt / PromptComposer.kt). Affects only on-device "what
 * else?" generation, so it lives in this section rather than as its own.
 */
@Composable
private fun GenderPicker(context: Context) {
    var gender by remember { mutableStateOf(GenderConfig.getGender(context)) }
    Text("Child's gender", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Used to phrase AI-generated suggestions naturally (e.g. \"talking to a small boy\").",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GenderConfig.Gender.entries.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    gender = option
                    GenderConfig.setGender(context, option)
                },
            ) {
                RadioButton(
                    selected = gender == option,
                    onClick = {
                        gender = option
                        GenderConfig.setGender(context, option)
                    },
                )
                Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Lets the caregiver edit, for the currently selected target language, the
 * two example demos (a `heard` phrase plus three `responses` each) that
 * steer the on-device model's style -- exactly what
 * few_shot_examples.caregiver_infant.by_language.json's own _comment
 * anticipated. Scoped to the active language only (not all
 * SupportedLanguages.ALL at once): the language picker above already lets
 * you switch which one you're editing, and most users only ever run one
 * target language at a time.
 *
 * Edits persist immediately per keystroke via FewShotExamplesConfig,
 * matching this screen's other fields. "Reset to default" clears the
 * override so PromptComposer falls back to the bundled asset demos again.
 */
@Composable
private fun FewShotExamplesEditor(context: Context, targetLanguage: TargetLanguage) {
    val languageCode = targetLanguage.code

    fun demoFields(demos: JSONArray, index: Int): Pair<String, List<String>> {
        val demo = demos.getJSONObject(index)
        val responses = demo.getJSONArray("responses")
        return demo.getString("heard") to (0 until responses.length()).map { responses.getString(it) }
    }

    var demo1Heard by remember(languageCode) { mutableStateOf("") }
    var demo1Responses by remember(languageCode) { mutableStateOf(listOf("", "", "")) }
    var demo2Heard by remember(languageCode) { mutableStateOf("") }
    var demo2Responses by remember(languageCode) { mutableStateOf(listOf("", "", "")) }
    var isOverride by remember(languageCode) { mutableStateOf(false) }

    // LaunchedEffect runs a coroutine tied to this composable's lifetime,
    // restarting it whenever its key (languageCode) changes -- used here to
    // load this language's saved examples once, rather than on every
    // recomposition.
    LaunchedEffect(languageCode) {
        val current = PromptComposer.currentExamplesForLanguage(context, languageCode)
        val (heard1, responses1) = demoFields(current, 0)
        val (heard2, responses2) = demoFields(current, 1)
        demo1Heard = heard1
        demo1Responses = responses1
        demo2Heard = heard2
        demo2Responses = responses2
        isOverride = FewShotExamplesConfig.getOverride(context, languageCode) != null
    }

    fun persist(heard1: String, responses1: List<String>, heard2: String, responses2: List<String>) {
        val demos = JSONArray()
            .put(JSONObject().put("heard", heard1).put("responses", JSONArray(responses1)))
            .put(JSONObject().put("heard", heard2).put("responses", JSONArray(responses2)))
        FewShotExamplesConfig.setOverride(context, languageCode, demos)
        isOverride = true
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Example phrases (${targetLanguage.displayName})", style = MaterialTheme.typography.bodyLarge)
            if (isOverride) {
                TextButton(onClick = {
                    FewShotExamplesConfig.clearOverride(context, languageCode)
                    val default = PromptComposer.defaultExamplesForLanguage(context, languageCode)
                    val (heard1, responses1) = demoFields(default, 0)
                    val (heard2, responses2) = demoFields(default, 1)
                    demo1Heard = heard1
                    demo1Responses = responses1
                    demo2Heard = heard2
                    demo2Responses = responses2
                    isOverride = false
                }) {
                    Text("Reset to default")
                }
            }
        }
        Text(
            "Two short examples shown to the on-device model so its suggestions match your preferred style. Edit either one to override the built-in default for this language.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FewShotDemoFields(
            label = "Example 1",
            heard = demo1Heard,
            responses = demo1Responses,
            onHeardChange = {
                demo1Heard = it
                persist(it, demo1Responses, demo2Heard, demo2Responses)
            },
            onResponseChange = { i, value ->
                val updated = demo1Responses.toMutableList().also { it[i] = value }
                demo1Responses = updated
                persist(demo1Heard, updated, demo2Heard, demo2Responses)
            },
        )
        FewShotDemoFields(
            label = "Example 2",
            heard = demo2Heard,
            responses = demo2Responses,
            onHeardChange = {
                demo2Heard = it
                persist(demo1Heard, demo1Responses, it, demo2Responses)
            },
            onResponseChange = { i, value ->
                val updated = demo2Responses.toMutableList().also { it[i] = value }
                demo2Responses = updated
                persist(demo1Heard, demo1Responses, demo2Heard, updated)
            },
        )
    }
}

@Composable
private fun FewShotDemoFields(
    label: String,
    heard: String,
    responses: List<String>,
    onHeardChange: (String) -> Unit,
    onResponseChange: (Int, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = heard,
            onValueChange = onHeardChange,
            label = { Text("Heard phrase") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        responses.forEachIndexed { i, response ->
            OutlinedTextField(
                value = response,
                onValueChange = { onResponseChange(i, it) },
                label = { Text("Response ${i + 1}") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

/**
 * Live download progress, if a download is currently running or queued --
 * WorkManager persists this across process death, so this reflects reality
 * even right after the app relaunches mid-download. See
 * ON_DEVICE_LLM_PLAN.md Phase 6.
 */
@Composable
private fun ModelDownloadStatus(context: Context) {
    val modelState = OnDeviceLlmConfig.getModelState(context)
    // getWorkInfosForUniqueWorkFlow returns a Flow -- a stream of values over
    // time (here, updated download status as it changes). collectAsState
    // bridges that stream into Compose state, so this composable
    // automatically recomposes each time WorkManager emits a new value.
    val workInfos by remember(context) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val activeWork = workInfos.firstOrNull { !it.state.isFinished }
    if (activeWork != null) {
        val progress = activeWork.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Downloading model... $progress%", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    } else if (modelState != OnDeviceLlmConfig.ModelState.READY) {
        Button(onClick = { ModelDownloadWorker.enqueue(context) }) {
            Text("Download model (2.7GB, WiFi only)")
        }
    } else {
        Text(
            "Model ready — used automatically for on-device \"what else?\" suggestions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
