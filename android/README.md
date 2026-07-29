# RU Bot Lisa — Android front end

Minimal single-screen Kotlin + Jetpack Compose app. Type or dictate a
transcript, it POSTs to `ingestion-service`'s `/event/voice` endpoint and
shows the returned Russian phrase.

## Run it

1. Start the backend locally (from the repo root):
   ```bash
   PYTHONPATH=. uvicorn services.retrieval_service.main:app --port 8001 &
   PYTHONPATH=. uvicorn services.orchestration_service.main:app --port 8002 &
   PYTHONPATH=. uvicorn services.ingestion_service.main:app --port 8003 &
   ```
2. Open the `android/` folder in Android Studio (Giraffe or newer) and let it sync.
3. Run on an emulator — no config needed, it already points at
   `http://10.0.2.2:8003`, which is how the emulator reaches your machine's
   `localhost:8003`.

### Running on a real device instead

Change `BASE_URL` in `app/src/main/java/com/rubotlisa/app/ApiClient.kt` to
your dev machine's LAN IP (e.g. `http://192.168.1.42:8003`), and make sure
the phone is on the same network and can reach that port.

## What's here

- `MainActivity.kt` — the whole UI: transcript field, mic button (uses
  Android's built-in speech-to-text intent, `ru-RU` locale), optional routine
  hint field, send button, response card.
- `ApiClient.kt` — OkHttp call to `POST /event/voice`, parses the JSON
  response into `AskResult`.

## Known limitations

- No auth, no retries, no offline handling — matches the backend's current
  "bare-bones scaffold" status.
- Mic input uses the system speech recognizer (requires Google app /
  network); it's not wired to the backend's vision/voice perception pipeline.
- `usesCleartextTraffic="true"` is set in the manifest since the backend
  runs over plain HTTP locally — tighten this before shipping anywhere real.
