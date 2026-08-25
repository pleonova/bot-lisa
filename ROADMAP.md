# Roadmap

Standalone feature bets that are bigger than a line item in the main
[README](README.md)'s "Suggested expansion order" — each of these touches
multiple layers of the system and needs its own design thinking before
work starts. Filed here so they're tracked instead of living only in a
conversation.

## Lisa Assistant — hands-free listening mode

**The vision:** before talking with your child, put in headphones, open
the app, and tap a single "Lisa Assistant" button. From then on, as you
speak, the app listens continuously and responds without further taps:

- Say something in **English** → it speaks back the Russian version.
- Say something in **Russian** → it speaks back the best next related
  phrase to try, so your active vocabulary keeps expanding in the moment.

Every suggestion made gets recorded and shown in a dashboard. If a phrase
Lisa suggested gets said out loud in a later utterance, that's an implicit
signal the suggestion was good — closing the loop between "here's a phrase
you might use" and "you actually used it."

**Status:** not started. Tracked here as its own multi-phase item rather
than a single checklist entry, since it touches mobile background audio,
speech recognition, text-to-speech, backend routing, event logging,
storage, and a new dashboard surface.

### What already exists to build on

- **Translate/expand routing already works.** `/assist`'s mode
  auto-detection (`_has_cyrillic()` in
  `services/orchestration_service/main.py`) already decides English-in vs.
  Russian-in per request. Assistant mode is largely a new front door onto
  this same backend logic, not a new backend concept.
- **Speaking translations aloud already works.** `TranslationSpeaker.kt`
  already speaks a translate-mode result the moment it comes back. Expand
  mode doesn't speak anything today — it just lists related phrases
  silently in the UI — so "speak the best next related phrase" needs a
  **pick-one** step first (see #3 below).
- **An event/pub-sub abstraction already exists** —
  `services/common/events.py`'s `PerceptionEvent` + `EventBus`, originally
  built for the paused child-perception voice pipeline. It's in-memory
  only today (nothing persists across a restart), but its publish/subscribe
  shape is exactly what a "log every suggestion" pipeline needs. Likely the
  natural place to hang usage logging off of, once given a real
  (Kafka/Redis/DB-backed) implementation per its own swap-in note.
- **An offline eval harness already exists** (`eval/run_eval.py`,
  NDCG/MRR/precision@k against `eval/labeled_eval_set.json`) — the natural
  home for turning real usage into better retrieval; see the feedback loop
  piece below.

### What's genuinely new and needs design

1. **Hands-free trigger + continuous background listening.** A "Lisa
   Assistant" toggle that starts a session and keeps listening without
   further taps, with output routed to headphones. On Android this means a
   foreground service (mic access while the app isn't in the foreground
   requires one, plus a persistent notification, per Android's background-
   mic rules) instead of the current one-shot `ACTION_RECOGNIZE_SPEECH`
   launched per mic tap.

2. **Real per-utterance language identification.** The decision this most
   depends on. Confirmed this session against the Android reference docs:
   `RecognizerIntent`/`SpeechRecognizer` has no official way to auto-detect
   which of two languages was spoken — it only ever listens in one fixed
   locale per session. Gboard's own multi-language voice typing does solve
   this, but only via its mic icon inside a focused text field; there's no
   keyboard involved in a hands-free background-listening flow, so that
   workaround doesn't carry over here. Continuous mode needs real
   utterance-level language ID, which realistically means one of:
   - A self-hosted, open-source ASR model with built-in per-segment
     language detection (e.g. Whisper / faster-whisper) running as a new
     backend service, paired with voice-activity detection to chop the
     audio stream into utterances. Keeps the project's zero-cloud-
     dependency, open-source-first stance, at the cost of needing a server
     with enough compute to run it.
   - A cloud STT API with native multi-language detection (e.g. Google
     Cloud Speech-to-Text's alternate-language-codes mode). Most accurate,
     least engineering effort — but a real reversal of "no external
     dependencies, no API keys," and adds per-use cost plus a network
     dependency in the middle of a conversation with your kid.

   Worth a dedicated decision before either gets built, given the
   project's stated preference for open-source tooling.

3. **Speak-back for expand mode.** Add a "pick the single best next
   phrase" step — today `/assist` returns a ranked list; the assistant
   needs to commit to one — and wire it into `TranslationSpeaker.kt` (or
   an equivalent) the same way translate mode already speaks its result.

4. **Usage logging.** Every suggestion the assistant makes (translation or
   related-phrase) needs to be recorded, not just spoken: timestamp,
   session, what was said, detected language, mode, and the phrase
   suggested. `PerceptionEvent`/`EventBus` is the likely plug-in point,
   upgraded from in-memory to something that survives a restart (an
   append-only file or a SQLite table is enough to start).

5. **Implicit feedback / scoring.** When a later utterance in the same
   session matches a previously suggested phrase, that's a signal the
   suggestion was good. Needs: a matching rule (exact string match on the
   Russian phrase text is the simplest starting point; fuzzy/normalized
   matching is a likely fast-follow once exact-match turns out too
   strict), a time window (how long after a suggestion does a repeat still
   count?), and a place for the resulting score to live. Simplest version:
   a "times reused" counter per phrase for the dashboard. More ambitious
   version: feed this into `eval/labeled_eval_set.json` as real-world
   relevance judgments, complementing the hand-labeled set the LTR
   reranker already trains against.

6. **Dashboard.** A view over the logged usage data — suggestions made,
   which ones got reused (from #5), phrase- or session-level trends over
   time. Doesn't need to be fancy to start — even a page reading straight
   from the event log covers the "recorded and tracked" ask.

### Suggested build order

\#3 (speak-back for expand mode) and #4 (usage logging) are the cheapest,
most decoupled pieces and are useful even in today's tap-to-use flow — they
don't need to wait on the harder assistant-mode work. #1 and #2
(hands-free + real language ID) are the two pieces that turn this from
"the existing app, logged" into an actual hands-free assistant; #2 in
particular needs the cloud-vs-self-hosted decision made first, since it
changes what infrastructure #1 needs to talk to. #5 and #6 depend on #4
existing first.
