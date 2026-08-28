# Assistant Lisa — UI redesign plan

Reference for reworking the single caregiver screen in
[`app/src/main/java/com/botlisa/app/MainActivity.kt`](app/src/main/java/com/botlisa/app/MainActivity.kt)
to match the attached wireframe. This is almost entirely presentation plus a
few new callbacks on the speech/TTS helpers — nothing in the backend,
networking, or trigger‑phrase logic changes.

*In other words: the app keeps doing exactly what it does today. This is a
new coat of paint (colors, the fox, a big mic button, a live transcript) on
top of the same plumbing.*

## Reference images

| Wireframe | Fox logo |
|---|---|
| ![Wireframe of the redesigned Assistant Lisa flow](design/wireframe.png) | ![Assistant Lisa fox logo](design/fox-logo.png) |

> **Add the two source images to this folder** (they are attachments, not yet
> committed): save them as `android/design/wireframe.png` and
> `android/design/fox-logo.png` so the links above resolve. The fox also
> becomes an in‑app asset and the launcher icon — see §2.

## Palette (from the wireframe legend)

| Role | Hex | Used for |
|---|---|---|
| Purple | `#8F7FEE` | `primary` — title, mic button while listening in Russian, translation speaker |
| Orange | `#FE9F4D` | accent — "Что ещё?" button, the recommendation currently being read |
| Green / teal | `#2CB3AE` | accent — "Как сказать?" button, mic button while listening for the English word |
| Off‑white | `~#F7F7FA` | window background, launcher‑icon background |

---

## Wireframe walkthrough (frame by frame)

1. **Idle / entry.** Header: fox logo top‑left, "Assistant Lisa" title in
   purple, gear icon top‑right. Centered subtitle "Tap for hands‑free mode".
   Large **grey** circular mic button, inactive. Rounded search field with a
   magnifier icon and placeholder "Enter English or Russian Text". Collapsed
   row: `>` "Hands‑free mode instructions".
2. **Instructions expanded.** Same screen, chevron rotated to `v`, a grey
   panel showing the hands‑free instructions paragraph and an italic "To edit
   voice commands, go to settings."
3. **Listening.** Subtitle "Listening (for Russian) …". Mic button **purple**
   and **pulsing**. A transcript pill sits directly under the button
   ("Transcript snippets always appear here"). Two command buttons at the
   bottom: teal **"Как сказать?"** with caption *How to say?*, orange
   **"Что ещё?"** with caption *What else?*.
4. **Listening + captured transcript.** Transcript pill now shows real text
   ("Пора чистить"). Annotation: "Always captures what is being said".
5. **Translate branch — trigger heard.** Transcript pill:
   "Пора чистить … как сказать" with an italic English gloss under it. Caption
   below the frame: "Speak in English after the beep".
6. **Recommendation branch — trigger heard.** Transcript pill:
   "Пора чистить зубки. Что ещё?" with italic gloss "Time to brush your teeth.
   What else?". Caption: "Listen to recommendation".
7. **Translate branch — listening for the word.** Subtitle "Heard the voice
   command, now say the English word(s)…". Mic button **teal**. Pill shows
   "teeth".
8. **Recommendation branch — reading results.** Subtitle "Heard the voice
   command, now listen to the recommendation". Speaker icon **grey/inactive**
   at top. Pill with the phrase + gloss, then a **"Recommendations:"** list:
   each row is a phrase pill with a trailing speaker icon; the one being read
   is **orange and pulsing**, the rest are grey.
9. **Translate branch — reading the translation.** Subtitle "Heard the
   English, now listen to the translation". **Purple** speaker icon (active).
   Pill shows the Russian word ("зубки") with the English caption ("teeth").

---

## 1. Gap analysis — wireframe vs. current UI

| Wireframe element | Today | Change |
|---|---|---|
| "Assistant Lisa" title, purple, fox logo left, gear right | "Bot Lisa" text + "Settings" `TextButton` | New header row; rename visible title; add logo; gear icon toggles settings |
| Dynamic subtitle (`Tap for hands-free mode` / `Listening (for Russian)…` / `…now say the English word(s)…` / `…now listen to the translation` / `…listen to the recommendation`) | Small state line inside the "Lisa Assistant" card (`Off` / `Listening (Russian)…` / …) | Promote to a centered subtitle driven by a richer UI‑phase state |
| Large central mic button — grey → purple → teal, pulsing while listening | Mic is a tiny trailing icon on the input; on/off is a `Start`/`Stop` text button | New `AssistantButton` composable: big circle, color per phase, infinite‑transition pulse |
| Transcript pill ("Пора чистить зубки. Что ещё?") + italic English gloss | No live transcript shown at all | New pill; needs an `onTranscript` callback added to `SpeechAssistant` |
| Collapsible "Hands-free mode instructions" with chevron | Instructions always visible as body text in the card | Collapsible section (`AnimatedVisibility`); **new copy** (see §7) with command phrases **bold** |
| Teal "Как сказать? / How to say?" + orange "Что ещё? / What else?" buttons, shown while listening | No tap equivalents for the spoken triggers | Two pill buttons; teal → enter translate mode, orange → `speakNextSuggestion()` |
| Result: purple speaker icon + big Russian word + English caption | Text‑only "Translation:" block with source/latency line | Restyle; add speaker icon that pulses while TTS speaks |
| Recommendations: list of phrase pills each with a speaker icon; the one being read is orange + pulsing | Bulleted `• phrase / gloss` list, no per‑item audio | Pill list with trailing speaker `IconButton`; track "currently speaking" index |
| Search‑style input: rounded, magnifier icon, "Enter English or Russian Text" | `OutlinedTextField` with label + mic trailing icon + separate "Look up" button | Restyle as a rounded search field; submit on IME action; drop the separate button and the one‑shot mic icon |
| Colors `#8F7FEE` / `#FE9F4D` / `#2CB3AE` | Bare `MaterialTheme {}` (system default purple) | New `BotLisaTheme` with a real `ColorScheme` |
| Fox app icon | `@android:mipmap/sym_def_app_icon` | Add the fox as an adaptive launcher icon + in‑app logo |

---

## 2. Theme & assets (new)

- **`ui/Theme.kt`** — `BotLisaTheme` wrapping `MaterialTheme` with a
  `lightColorScheme` (and a matching `darkColorScheme` if we want dark
  support): `primary = #8F7FEE`, `secondary` / `tertiary = #FE9F4D` and
  `#2CB3AE`, plus sensible `onPrimary` / container roles. Wire it in
  [`MainActivity.onCreate`](app/src/main/java/com/botlisa/app/MainActivity.kt#L68-L77)
  in place of the bare `MaterialTheme {}`.
- **[`res/values/themes.xml`](app/src/main/res/values/themes.xml)** — change
  the base to a `DayNight.NoActionBar` parent with the off‑white
  (`~#F7F7FA`) window background.
- **Fox logo asset** — save the source PNG as
  `res/drawable-nodpi/lisa_fox.png`; render in the header via
  `Image(painterResource(...))` at ~32–40 dp.
- **Launcher icon** — `mipmap-anydpi-v26/ic_launcher.xml` +
  `ic_launcher_round.xml` adaptive icon: foreground = fox (scaled ~66 %),
  background = solid `#F7F7FA`. Point `android:icon` / `android:roundIcon` in
  [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml#L10) at it.
- **[`res/values/strings.xml`](app/src/main/res/values/strings.xml)** — add
  `assistant_title` = "Assistant Lisa"; optionally change `app_name` too
  (see §8). Package stays `com.botlisa.app`.

No new Gradle dependencies — `material-icons-extended` (Search, Settings,
VolumeUp, ExpandMore) and the animation APIs are already on the classpath via
the Compose BOM.

---

## 3. Screen rewrite — [`LisaScreen()`](app/src/main/java/com/botlisa/app/MainActivity.kt#L82)

Still one scrolling `Column`, tighter spacing to match the mock.

1. **Header row** — `Image(fox)` + `Text("Assistant Lisa", color = primary)`
   on the left; `IconButton(Icons.Filled.Settings)` on the right toggling
   `showServerSettings`. Removes the "Bot Lisa" / "Hide settings" row at
   [L349‑L358](app/src/main/java/com/botlisa/app/MainActivity.kt#L349-L358).
2. **Subtitle** — centered `Text`, value from the new `uiPhase` (below).
   Replaces the descriptive paragraph at
   [L359‑L363](app/src/main/java/com/botlisa/app/MainActivity.kt#L359-L363).
3. **`AssistantButton(uiPhase, onClick = ::onToggleAssistant)`** — new
   composable in `ui/AssistantButton.kt`. Circle ~84 dp; `Mic` icon in
   listening phases, `VolumeUp` icon in speaking phases; fill color
   grey / purple / teal per phase; `rememberInfiniteTransition` scaling a
   ring behind it while the phase is a "listening" or "reading" one. Absorbs
   the current `Start`/`Stop` `Button` at
   [L387‑L389](app/src/main/java/com/botlisa/app/MainActivity.kt#L387-L389);
   `onToggleAssistant()`
   ([L326‑L340](app/src/main/java/com/botlisa/app/MainActivity.kt#L326-L340))
   is reused as‑is.
4. **Transcript pill** — `Surface(shape = pill)` showing `lastTranscript`
   (new state), with an optional second italic line for the English gloss.
   Hidden when empty.
5. **Collapsible instructions** — clickable Row (chevron + "Hands‑free mode
   instructions") + `AnimatedVisibility` panel containing the **new copy**
   in §7, with the command phrases rendered **bold**. Replaces the always‑on
   text at
   [L391‑L396](app/src/main/java/com/botlisa/app/MainActivity.kt#L391-L396).
   New `var showInstructions by rememberSaveable { mutableStateOf(false) }`.
6. **Voice command buttons** — `Row` of two pill `Button`s, shown only when
   `assistantState != IDLE`:
   - Teal **"Как сказать?"** / caption *How to say?* → new
     `assistant.enterTranslateMode()` (thin public method doing what the
     translate‑trigger branch does at
     [`SpeechAssistant.kt` L121‑L124](app/src/main/java/com/botlisa/app/SpeechAssistant.kt#L121-L124)).
   - Orange **"Что ещё?"** / caption *What else?* → `speakNextSuggestion()`
     ([L264‑L273](app/src/main/java/com/botlisa/app/MainActivity.kt#L264-L273)).
7. **Search input** — `OutlinedTextField` (or `BasicTextField` in a pill
   `Surface`) with `leadingIcon = Icons.Filled.Search`, placeholder
   "Enter English or Russian Text",
   `KeyboardOptions(imeAction = ImeAction.Search)`,
   `keyboardActions = KeyboardActions(onSearch = { onSend() })`. Drops the
   mic trailing icon and the separate "Look up" `Button` at
   [L470‑L491](app/src/main/java/com/botlisa/app/MainActivity.kt#L470-L491).
   Keep `LinearProgressIndicator` for the loading state.
8. **Result card** — rework
   [L501‑L546](app/src/main/java/com/botlisa/app/MainActivity.kt#L501-L546):
   - translate mode: speaker `IconButton` (pulses while TTS active) +
     `Text(translation.ru, headlineSmall)` +
     `Text(input, bodySmall, italic)`. Drop the `source · latencyMs` line
     (or move it under settings).
   - expand / "Recommendations": label + `r.related` rendered as pill rows,
     each `Row(phrase.ru + gloss, trailing speaker IconButton)`. The row
     whose index `== speakingIndex` gets an orange container + pulse.
     Speaker tap calls a new `speakRelated(i)` that sets `speakingIndex = i`
     and speaks; `speakNextSuggestion()` also updates `speakingIndex`.
9. **Settings panel** — unchanged content (target language, two trigger
   fields, server URL, API key at
   [L403‑L468](app/src/main/java/com/botlisa/app/MainActivity.kt#L403-L468));
   just now revealed by the gear icon. Optionally move into its own `Dialog`
   in the polish step (see §8).

### New state in `LisaScreen`

```kotlin
var lastTranscript by rememberSaveable { mutableStateOf("") }
var showInstructions by rememberSaveable { mutableStateOf(false) }
var speakingIndex by remember { mutableStateOf<Int?>(null) }
var isSpeaking by remember { mutableStateOf(false) }
// uiPhase: derived from assistantState + isSpeaking + result?.mode
```

`uiPhase` is a small `enum` (`IDLE`, `LISTENING_RU`, `LISTENING_EN`,
`SPEAKING_TRANSLATION`, `READING_RECOMMENDATION`) computed with
`derivedStateOf` and consumed by the subtitle + `AssistantButton`.

---

## 4. Supporting‑class changes

- **[`SpeechAssistant.kt`](app/src/main/java/com/botlisa/app/SpeechAssistant.kt)**
  - Add constructor param `onTranscript: (String) -> Unit` and call it from
    `onResults`
    ([L145‑L151](app/src/main/java/com/botlisa/app/SpeechAssistant.kt#L145-L151))
    — and optionally from `onPartialResults` after enabling
    `EXTRA_PARTIAL_RESULTS` — so the transcript pill updates live, including
    for the command phrases.
  - Add `fun enterTranslateMode()` that runs the same transition as the
    translate‑trigger branch (`state = LISTENING_FOR_WORD`,
    `listenOnce(translateLanguageCode)`), for the teal button.
- **[`TranslationSpeaker.kt`](app/src/main/java/com/botlisa/app/TranslationSpeaker.kt)**
  - Set an `UtteranceProgressListener` on `tts` and add
    `onStart` / `onDone` / `onError` callbacks (or a
    `StateFlow<Boolean> speaking`) so the UI can pulse the speaker icon and
    show the "…now listen to the translation" subtitle only while audio is
    actually playing. `speak()` already tags utterances with an id
    ([L48](app/src/main/java/com/botlisa/app/TranslationSpeaker.kt#L48)).
- **[`ApiClient.kt`](app/src/main/java/com/botlisa/app/ApiClient.kt) / backend**
  — no change.

---

## 5. New files

- `ui/Theme.kt` — `BotLisaTheme`, color schemes, typography tweak (purple
  title).
- `ui/AssistantButton.kt` — pulsing circular mic / speaker button.
- `ui/TranscriptPill.kt`, `ui/VoiceCommandButtons.kt`,
  `ui/RecommendationList.kt` — optional extraction to keep `MainActivity.kt`
  readable (it is ~550 lines now).
- `res/drawable-nodpi/lisa_fox.png`, `res/mipmap-anydpi-v26/ic_launcher*.xml`,
  `res/values/ic_launcher_background.xml`.
- Edits: `res/values/themes.xml`, `res/values/strings.xml`,
  `AndroidManifest.xml`.

---

## 6. Suggested build order

Each step leaves the app buildable.

1. **Theme + fox + rename** — `Theme.kt`, colors wired in `setContent`,
   launcher icon, "Assistant Lisa" title. Immediately visible, low risk.
2. **Header + gear + dynamic subtitle** — new top row, `uiPhase` enum,
   settings behind the gear.
3. **AssistantButton** — big pulsing button, retire `Start`/`Stop`.
4. **Transcript pill** — `onTranscript` callback + state + pill (English
   gloss line deferred).
5. **Collapsible instructions** — new copy, bold commands.
6. **Voice command buttons** — teal / orange, `enterTranslateMode()` +
   `speakNextSuggestion()`.
7. **Search‑style input** — restyle, IME submit, drop "Look up" + old mic
   icon.
8. **Result / recommendations rework** — speaker icons,
   `UtteranceProgressListener`, currently‑speaking highlight + pulse.
9. **Polish** — spacing to match the mock, dark‑mode pass, optional
   settings‑as‑dialog.

Steps 1–7 are largely independent; 8 is the biggest single chunk.

---

## 7. Hands‑free instruction copy (replaces the old text)

Shown in the collapsible "Hands‑free mode instructions" panel (frame 2) and
nowhere else. The **command phrases are bold** in the app. When the user has
customised a trigger phrase in Settings, the bold text is that custom phrase,
not the literal default — build it from `translateTriggerPhrase` /
`nextSuggestionTriggerPhrase`.

> **Hands‑free mode:**
> - Tap the mic, then speak Russian normally
> - To translate a word: say **как сказать**, pause and wait for the beep, then say the English word
> - To hear the next suggestion: say **что ещё?**
>
> To edit voice commands, go to settings.

### Compose rendering

```kotlin
val instructions = buildAnnotatedString {
    append("Hands-free mode:\n")
    append("• Tap the mic, then speak Russian normally\n")
    append("• To translate a word: say ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(translateTriggerPhrase) }
    append(", pause and wait for the beep, then say the English word\n")
    append("• To hear the next suggestion: say ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(nextSuggestionTriggerPhrase) }
    appendLine()
    appendLine()
    append("To edit voice commands, go to settings.")
}
Text(instructions, style = MaterialTheme.typography.bodySmall)
```

> **"Wait for the beep"** — the copy promises an audible cue when the app
> switches to listening for the English word. There is no beep today; add a
> short `ToneGenerator` blip (or a `TextToSpeech` earcon) in
> `enterTranslateMode()` / the translate‑trigger branch of `SpeechAssistant`
> as part of step 6, or soften the wording.

---

## 8. Decisions to confirm (defaults chosen)

- **App label** — rename the on‑screen title to "Assistant Lisa" only, or
  also `app_name` (home‑screen label)? *Default: rename both; package stays
  `com.botlisa.app`.*
- **English gloss under the transcript pill** ("Time to brush your teeth.
  What else?") — showing it live needs on‑the‑fly RU→EN translation of the
  transcript (ML Kit can, model download on first use). *Default: ship the
  pill with raw transcript now, add the gloss line as a follow‑up.*
- **Typed input + one‑shot dictation** — the wireframe drops the "Look up"
  button and the input's mic icon. *Default: keep typing as a fallback that
  submits on the keyboard's Search action; remove the standalone one‑shot
  `speechLauncher` mic (the big button is the mic now).*
- **Settings** — inline expanding section (current) vs. a dedicated dialog /
  screen. *Default: inline for now, dialog in the polish step.*
- **Dark theme** — the wireframe is light‑only. *Default: define both
  schemes but tune only light.*
- **Beep** — add a real audible cue vs. reword the instructions. *Default:
  add a short tone in step 6.*
