# Bot Lisa — Android front end

Minimal single-screen Kotlin + Jetpack Compose app for the **caregiver**, not
the child-perception pipeline. One text box (type or dictate):

- Type/say an **English** word or phrase -> get its baby-register Russian
  translation. Checks the curated phrase library first (consistent, vetted
  wording); only falls back to the LLM when nothing in the library matches.
- Type/say a **Russian** phrase -> get related phrases from the library, so
  you can expand your own active vocabulary around what you just said.

Which mode you get is auto-detected on the backend (presence of Cyrillic
characters) — no mode toggle needed in the app.

This talks to `orchestration-service`'s `POST /assist` endpoint directly
(port 8002), not `ingestion-service`'s `/event/voice` (that endpoint still
exists for the separate child-perception-event flow, just unused by this app
right now).

*In other words: this app is one screen with one text box. Type an English word
and get a Russian translation, or type/say a Russian phrase and get related
phrases to expand your vocabulary. It reaches the backend over the internet
(or your local network) at a specific address — no built-in server of its
own.*

## Run it

1. Start the backend locally (from the repo root):
   ```bash
   PYTHONPATH=. uvicorn services.retrieval_service.main:app --port 8001 &
   PYTHONPATH=. uvicorn services.orchestration_service.main:app --port 8002 &
   ```
   (`ingestion-service` on 8003 isn't required for this app.)
2. Open the `android/` folder in Android Studio (Giraffe or newer) and let it sync.
3. Run on an emulator — no config needed, it already points at
   `http://10.0.2.2:8002`, which is how the emulator reaches your machine's
   `localhost:8002`. Voice input needs a *Google Play* system image on the
   AVD (not just "Google APIs") — see "Known limitations" below.

*In other words: first two commands start the backend on your own computer (no
cloud needed for local testing). Android Studio is the program that builds
and runs the app. An "emulator" is a fake phone that runs on your computer —
it has its own way of reaching "your computer's localhost," which is why
the address looks unusual (`10.0.2.2` instead of `localhost`).*

### Running on a real device instead

Tap "Server settings" in the app and change the URL to your dev machine's
LAN IP (e.g. `http://192.168.1.42:8002` — find it with `ipconfig getifaddr en0`
on Mac). It's saved automatically (SharedPreferences), no rebuild needed. Make
sure the phone is on the same Wi-Fi network and can reach that port.

**If you already ran an earlier version of this app**, your saved server URL
may still point at the old default (`:8003`). Open "Server settings" and
update it to `:8002` (or your real device's equivalent) manually — the new
default only applies to fresh installs.

*In other words: a real phone can't use the emulator's special `10.0.2.2`
address — it needs your computer's actual network address instead, since
they're two separate physical devices on the same Wi-Fi. If the app still
remembers an old address from a previous install, just retype it.*

## What's here

- `MainActivity.kt` — the whole UI: input field, mic button (uses Android's
  built-in speech-to-text intent, `ru-RU` locale), collapsible server URL
  setting, "Look up" button, response card (shows a single translation +
  related phrases in translate mode, or just a related-phrases list in expand
  mode).
- `ApiClient.kt` — OkHttp call to `POST /assist`, parses the JSON response
  into `AssistResult`. Takes the base URL as a parameter rather than
  hardcoding it.
- `ServerConfig.kt` — persists the server base URL in SharedPreferences,
  defaulting to the emulator alias `http://10.0.2.2:8002`.
- `OnDeviceTranslator.kt` — on-device English->Russian translation via
  Google's ML Kit, used only when `/assist` reports no curated-library
  match (`source == "mock"`). Runs fully offline after a one-time model
  download; no API key, no per-call cost. Produces standard/textbook
  Russian, not the curated library's baby-register tone -- see the project
  roadmap for that tradeoff and the option to swap in a live LLM fallback
  later instead.
- `TranslationSpeaker.kt` — speaks the translation aloud via Android's
  built-in TextToSpeech the moment a translate-mode result comes back
  (curated or on-device, whichever won). Built for the "one earbud in,
  talking to the kid" use case — no extra tap needed, and it plays through
  whatever audio output is currently active (earbud/Bluetooth/speaker) since
  that's just how Android routes audio. Voice follows the target-language
  setting (see below).
- `LanguageConfig.kt` — the list of selectable target languages (Russian,
  Hindi, Marathi, Spanish, French, German today — add an entry to extend it)
  and the
  SharedPreferences-backed setting for which one is active. Drives
  `OnDeviceTranslator.kt` and `TranslationSpeaker.kt`. Does **not** drive
  related-phrase/expand mode, which stays tied to the Russian curated
  library regardless — see "Known limitations."

*In other words: three files, three jobs. One draws the screen you see, one
sends the actual network request to the backend, and one just remembers
your saved settings (like the app's own tiny notepad) so you don't have to
retype them every time you open the app.*

## Known limitations

*In other words: this is a working scaffold, not a polished product — it
doesn't retry failed requests, doesn't work offline, and its one security
measure is a single shared password rather than individual logins.*

- No retries, no offline handling — matches the backend's current
  "bare-bones scaffold" status. There is a simple shared-secret API key (see
  "Server settings" and `PHONE_DEPLOY.md`) guarding the deployed backend, but
  it's one key for all users, not per-user auth.
- Mic input uses the system speech recognizer (requires Google app /
  network), locale fixed to `ru-RU` — dictating English will likely
  mis-transcribe; typing English works fine.
- Voice doesn't work on most emulator AVDs — confirmed working fine on a
  real phone, but the default/"Google APIs" system images most AVDs use
  don't include Google's speech-recognition service at all, so the mic
  button just fails with "No speech recognizer available on this device."
  Use a "Google Play" system image and sign into a Google account inside
  the emulator if you need voice testing without a physical phone;
  otherwise treat the emulator as text-only and test voice on the real
  device.
- Curated-vs-LLM-fallback classification in translate mode is a simple
  word-overlap check against `gloss_en` (see `assist()` in
  `services/orchestration_service/main.py`) — it's a heuristic, not perfect,
  and will misclassify some inputs as the phrase library grows.
- The on-device translation fallback (`OnDeviceTranslator.kt`) requires wifi
  the very first time it's used, to download the ~30MB model for each
  target language; after that, that language works fully offline. It only
  ever fires for English input in translate mode — Russian input (expand
  mode) is untouched.
- Target language (Settings → "Target language") only changes what English
  translates into and which voice speaks it back. Related-phrase/expand
  mode — say a phrase in the target language, get similar ones from the
  curated library — stays Russian-only no matter what's selected there,
  since the curated content is Russian-only and the backend's mode-detection
  heuristic (`_has_cyrillic()`) is Cyrillic-specific. Generalizing that is a
  separate, larger change (curate content for the new language, and make
  the backend's script detection language-aware) tracked in the project
  roadmap, not done here. The mic's dictation locale is unaffected by this
  setting too, for the same reason — it still always listens for Russian.
  Switching target language away from Russian also hides the "more related
  phrases" bonus list under a translation result, since those phrases would
  be Russian regardless of what you just translated into.
- `usesCleartextTraffic="true"` is set in the manifest since the backend
  runs over plain HTTP locally — tighten this before shipping anywhere real.
