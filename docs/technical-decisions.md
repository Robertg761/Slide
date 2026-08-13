# Slide — Technical Decisions & Risks

Companion to `gboard-parity.md`. Covers the three things that decide whether this project works.

---

## 0. Scope decisions (locked 2026-08-07)

- **Own gesture decoder.** Distribution is undecided but sharing is likely, so the extracted Gboard
  blob is off the table from day one — adopting it would have to be undone later anyway.
- **English only for v1.** Layouts and dictionaries stay data-driven so more languages are additive.
- **Offline typing and speech.** The model is bundled and typing data is never sent to a service.
  The app has network permission only for the optional GitHub release checker. This removes GIF
  search (F7) and translate (G11) from v1; neither was a stated priority.

---

## 1. Swipe typing — the one real blocker

**There is no usable open-source glide-typing decoder.** This is worth knowing before anything else
is built.

Google never open-sourced the gesture decoder in AOSP LatinIME. The keyboards that appear to have
working swipe (HeliBoard, OpenBoard forks) get it by loading `libjni_latinimegoogle.so` — a closed
binary extracted from Gboard. That blob:

- cannot legally be redistributed,
- cannot go on the Play Store,
- is unmaintained and ties us to Google's dictionary format.

So we write our own decoder. That is genuinely achievable — the algorithm is published (SHARK²,
Zhai & Kristensson) and it's the approach Google's own decoder descends from. Plan:

**Pipeline**
1. **Capture** — touch points `(x, y, t)` at display rate; light smoothing.
2. **Resample** — normalise the path to a fixed 100 equidistant points.
3. **Prune** — candidate words must plausibly start near the first key and end near the last key
   (these two are by far the most reliable signals). Walk a trie of the lexicon, keeping only
   branches whose keys the path passes near. Cuts 100k words to a few hundred.
4. **Score** each candidate on three channels:
   - *shape* — scale/translation-invariant distance between the user path and the word's ideal
     path (its key centres, resampled identically),
   - *location* — absolute proximity, which disambiguates same-shape words,
   - *language* — unigram frequency plus bigram probability given the previous word.
   Combine log-linearly with tuned weights.
5. **Emit** top-3; best one goes inline, others to the suggestion strip.

**Tuning data.** We can synthesise a gesture corpus — interpolate through key centres with
realistic curvature, overshoot, and jitter — and tune the weights against it, then refine on real
gestures once the app is usable. This is the standard bootstrap.

**Performance.** Target <30 ms from finger-up to candidates. Prototype in Kotlin; if the trie walk
is too slow, move the hot loop to C++ or Rust over JNI. Live mid-gesture preview (B4) means running
a cheaper version of this every ~50 ms during the swipe.

**Honest assessment**: this is the highest-effort item in the project — realistically a few weeks to
"works", and continuous tuning after that to reach the accuracy people expect from Gboard. It's
also the thing that makes Slide worth using, so it should be built early rather than last.

---

## 2. Dictionaries and the language model

The decoder and autocorrect both need a wordlist with frequencies plus an n-gram model.

**Source**: the AOSP dictionaries (Apache-2.0) are the practical starting point — ~50 languages,
already frequency-weighted, and license-clean. The `aosp-dictionaries` project maintained for
HeliBoard has current builds. We'll convert them to our own format rather than adopting AOSP's
binary dictionary format.

**Bigrams**: the AOSP combined wordlist turns out to carry none at all, despite the format
supporting them — the shipped `en_wordlist.combined` is words and frequencies only. English bigrams
are therefore built from [Tatoeba](https://tatoeba.org)'s sentence export (CC BY 2.0 FR): everyday
conversational writing, which is a far closer match to what people type into a phone than books or
an encyclopaedia, and permissive enough to redistribute a derived model. About 12M in-lexicon
adjacencies reduce to 400k pairs over 24k contexts in a 1.3MB asset, keyed by lexicon index.

The context term is added to a candidate's score and never subtracted, so a pair the model has not
seen leaves that candidate exactly where spelling put it. Only what the model positively knows can
move anything, which is what makes it safe to consult a model with large gaps — and every model of
this size is mostly gaps. On held-out sentences with synthetic nonword single-edit typos, it takes
autocorrect from 84.4% to 92.2%. Swipe decoding moves from 93.8% to 96.8% top-1; context is the only
channel that can separate words tracing an identical path.

A corpus brings its own distortions, and one here was large enough to reach the user. Tatoeba uses
Tom and Mary as stock characters and Boston as its stock city, to the point that "tom" is the third
commonest token in the whole corpus, ahead of "I" and "you". Left in, the strip offered "thank tom"
and "fom" was corrected to "tom". Pairs containing them are excluded at build time. Doing so very
slightly lowers the measured correction rate, because the held-out sentences carry the same skew —
that is the measurement losing an artefact rather than the model losing accuracy.

Adaptive personal n-grams accumulate locally on top, as words and as pairs. The pair model is
string-keyed rather than index-keyed, because half its value is in pairs containing words no
lexicon has. Its threshold is the one number here settled by measurement rather than by reasoning:
acting on a pair seen once cost roughly twice as many wrong corrections as it bought right ones on
held-out text, so nothing counts below several sightings. The benefit it exists for — a habit no
corpus contains — is not measurable against a corpus, and is demonstrated on the mechanism instead.

**The touch model**: corrections are priced by where the finger actually landed rather than by
which keys are adjacent. The static version — cost proportional to the distance between two key
centres — says the same thing about every press of a key, when the interesting question is how
close *this* press came to the key next door. Measured against simulated typing in which the
pressed key falls out of the sampled touch position, so mis-hits arise from geometry rather than
from a hand-written list, this corrects several points more of them and slightly fewer wrongly, at
every level of sloppiness. The magnitude should not be quoted as fact: real fingers are not
isotropic Gaussians and carry a systematic bias this simulation has none of. Settling it needs
touch logs from a device, which Slide does not collect.

**Do not** copy anything from Gboard itself — no dictionaries, no assets, no code.

### Personal swipe adaptation and measurement

Swipe alternatives are now adaptive only after unambiguous feedback. A verified suggestion-strip
replacement teaches a bounded preference between the rejected and chosen candidates; an immediate
whole-word Backspace is weaker rejection evidence and must repeat before it can demote a result.
The adjustment operates in rank space after either neural or deterministic decoding, because their
raw scores are not calibrated to the same scale. Evidence saturates, decays by logical feedback
epochs rather than wall-clock time, and is capacity-bounded so old habits can disappear and state
cannot grow forever.

The persisted snapshot contains a per-install salt, salted word fingerprints, bounded strengths,
and logical ages. It contains no raw trace, coordinates, surrounding context, app/editor identity,
or timestamps; it is excluded from backup and participates in the same fail-closed save/delete
transaction as other learned data. Incognito fields and fields requesting no personalized learning
never train it.

Quality measurement has two separate privacy boundaries. The IME keeps only fixed-size process-local
aggregate buckets for latency, candidate count, confidence, decoder provenance, outcomes, feedback,
and model readiness. The opt-in benchmark JSONL contains content-free outcomes and rejects unknown
fields, while `tools/typing_quality_report.py` calculates accuracy, wrong commits, abstentions,
fallback, latency percentiles, calibration, before/after deltas, and explicit regression budgets.
Synthetic and donated-corpus reports remain benchmarks, not evidence of physical-device UX.

---

## 3. On-device Whisper

### Model choice

Slide packages one model: `ggml-base.en-q5_1.bin` (59,721,011 bytes). A prior Galaxy S24
Ultra benchmark loaded Base in about 100 ms and decoded an 11-second fixture in about 1.7 seconds.
The former Small model decoded the same fixture in about 7.4 seconds and added roughly 181 MB to
every APK and update, so it was removed. This is one device measurement, not a latency promise for
all supported devices.

The build fetch is pinned to immutable whisper.cpp model revision
`5359861c739e955e79d9a303bcbc70fb988958b1` and verifies SHA-256
`4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f`. The model is bundled
and stored uncompressed for direct asset access; the installed app has no model downloader or
model-choice UI.

Current transcription is batch-oriented: the user starts and stops recording, then Whisper decodes
the captured 16 kHz mono audio. A conservative local pre-pass trims only confidently silent leading
and trailing frames. Low-confidence temperature retries remain available but are explicitly bounded.
ARM64 packages separately compiled ggml CPU backends and asks each for a compatibility score before
loading the best match, preserving the Armv8 baseline while allowing dot-product, FP16, i8mm, SVE,
and SME kernels on newer phones. Streaming partials, VAD endpointing, and GPU/NPU acceleration are
future work and must not be described as shipped features.

### Process architecture

**Run the ASR in a separate process** (`:asr`), bound from the IME via a `Service`.

An `InputMethodService` that holds a model and native inference state is a prime candidate for the
low-memory killer — and when the IME process dies, the keyboard vanishes mid-sentence. A
separate process lets us load on mic-press, unload aggressively, and survive its death without
taking the keyboard with it.

### Gotchas

- **`RECORD_AUDIO` cannot be requested from the keyboard view.** Slide launches its transparent
  `MicPermissionActivity` for the system prompt, then the IME rechecks the result.
- Use `MediaRecorder.AudioSource.VOICE_RECOGNITION`, 16 kHz mono PCM — that's what Whisper expects.
- The model is bundled in the APK and verified during the build; there is no runtime model fetch.
- The speech module has no Internet permission, URL client, platform recognizer, or cloud fallback.
- Licensing is clean: Whisper weights are MIT, `whisper.cpp` is MIT.

---

## 4. Stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin | |
| Min SDK | 26 (Android 8) | ~98% coverage; Material You degrades gracefully below 31 |
| Keyboard surface | Custom `View` + Canvas | Precise touch handling and frame budget; Compose is the wrong tool for a key grid |
| Settings & panels | Jetpack Compose + Material 3 | Fast to build, dynamic colour for free |
| ASR runtime | `whisper.cpp` via NDK/CMake + JNI | Mature, quantised, mmap, actively maintained |
| Endpointing | Manual stop plus a recording limit | VAD is not yet implemented |
| Decoder | Kotlin first, native if profiling demands | |
| Persistence | DataStore + private files | Settings, recent emoji, learned words and phrases |
| Build | Gradle KTS, version catalog | |

**Module layout**

```
:app          setup wizard, settings UI
:ime          InputMethodService, views, touch, themes
:engine       gesture decoder, autocorrect, prediction, lexicon
:asr          Whisper service (separate process), recorder, bundled-model transcriber
:core         design tokens, layout schema, shared types
```

The theming system (H14) should be a token layer in `:core` from day one — retrofitting themes
across the keyboard, emoji panel, clipboard, and voice UI later is painful, and "nice themes" is a
stated v1 goal.

---

## 5. Legal boundary

Clone the *functionality*, not the *implementation*. Specifically:
- no Gboard binaries, dictionaries, sound files, or theme images,
- Emoji Kitchen, Bitmoji, and Minis assets are Google/Snap property → not cloned,
- Google Search and Lens integration are proprietary → not cloned,
- GIF search needs a Tenor API key (available, but it's a network feature).

Everything on the V1 list is buildable from open or original sources.

---

## 6. Suggested build order

1. **Skeleton IME** — installs, activates, types on QWERTY, has a real theme system. (A1–A9, H1–H3, H14)
2. **Lexicon + prediction + autocorrect.** (D1–D8) — the decoder needs this substrate.
3. **Gesture decoder.** (B1–B6, B10–B12) — the long pole; start it as soon as step 2 lands.
4. **Whisper voice input.** (C1–C7, C9, C14) — parallelisable with step 3, different subsystem.
5. **Themes, feedback, appearance settings.** (H4–H15, I1–I3)
6. **Toolbar, clipboard, text editing, emoji.** (G1–G6, G14, F1–F6)
7. **Setup wizard, accessibility, polish.** (J7, K8)

Steps 3 and 4 are the version-1 product. Everything else is table stakes around them.
