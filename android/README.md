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
   `localhost:8002`.

### Running on a real device instead

Tap "Server settings" in the app and change the URL to your dev machine's
LAN IP (e.g. `http://192.168.1.42:8002` — find it with `ipconfig getifaddr en0`
on Mac). It's saved automatically (SharedPreferences), no rebuild needed. Make
sure the phone is on the same Wi-Fi network and can reach that port.

**If you already ran an earlier version of this app**, your saved server URL
may still point at the old default (`:8003`). Open "Server settings" and
update it to `:8002` (or your real device's equivalent) manually — the new
default only applies to fresh installs.

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

## Known limitations

- No auth, no retries, no offline handling — matches the backend's current
  "bare-bones scaffold" status.
- Mic input uses the system speech recognizer (requires Google app /
  network), locale fixed to `ru-RU` — dictating English will likely
  mis-transcribe; typing English works fine.
- Curated-vs-LLM-fallback classification in translate mode is a simple
  word-overlap check against `gloss_en` (see `assist()` in
  `services/orchestration_service/main.py`) — it's a heuristic, not perfect,
  and will misclassify some inputs as the phrase library grows.
- `usesCleartextTraffic="true"` is set in the manifest since the backend
  runs over plain HTTP locally — tighten this before shipping anywhere real.
