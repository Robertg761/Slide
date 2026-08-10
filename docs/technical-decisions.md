# Slide — Technical Decisions & Risks

Companion to `gboard-parity.md`. Covers the three things that decide whether this project works.

---

## 0. Scope decisions (locked 2026-08-07)

- **Own gesture decoder.** Distribution is undecided but sharing is likely, so the extracted Gboard
  blob is off the table from day one — adopting it would have to be undone later anyway.
- **English only for v1.** Layouts and dictionaries stay data-driven so more languages are additive.
- **Fully offline.** No network permission except explicit model downloads. This removes GIF search
  (F7) and translate (G11) from v1; neither was a stated priority.

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

---

## 3. On-device Whisper

### Model choice

Recommendation, using `whisper.cpp` GGML quantised weights:

| Tier | Model | Size (q5_1) | Use |
|---|---|---|---|
| Fast | `base.en` | ~57 MB | Older/mid devices, lowest latency |
| **Default** | **`small.en`** | **~180 MB** | **Best accuracy/latency balance on modern phones** |
| Best | `large-v3-turbo` (q5_0) | ~570 MB | Flagships only; optional download |
| Multilingual | `small` / `large-v3-turbo` | ~190 MB / ~570 MB | When non-English is enabled |

`small.en` is the sweet spot: it's where Whisper stops making embarrassing errors, and it's still
fast enough to feel instant on a recent Snapdragon/Tensor with the optimisations below. `medium` is
not worth it — roughly 3× the cost of `small` for a small accuracy gain, and `large-v3-turbo` beats
it anyway. Since you don't care about download size, we offer all four and default to `small.en`.

These throughput expectations need measuring on your actual device before we commit — I'd rather
benchmark early than design around a guess.

### Making it feel instant

Two optimisations matter more than model choice:

- **Reduced `audio_ctx`.** Whisper's encoder always processes a padded 30-second window, so a
  2-second utterance costs the same as a 30-second one by default. Shrinking `audio_ctx`
  proportionally to the actual audio length cuts encoder time several-fold with minimal accuracy
  loss. This is the single biggest win for dictation latency.
- **Chunked pseudo-streaming.** Whisper isn't a streaming model. We run a sliding window (~5 s with
  ~1 s overlap) to emit partial text while the user speaks, then re-decode the final segment at full
  settings on endpoint. The user perceives near-zero latency because only the last chunk is pending
  when they stop.

Plus: VAD for endpointing (Silero VAD ONNX, ~2 MB), `n_threads` pinned to the big cores, and
mmap'd model loading. Vulkan/NNAPI/QNN acceleration is a later optimisation, not a v1 dependency.

### Process architecture

**Run the ASR in a separate process** (`:asr`), bound from the IME via a `Service`.

An `InputMethodService` that holds 300–600 MB of model and inference state is a prime candidate for
the low-memory killer — and when the IME process dies, the keyboard vanishes mid-sentence. A
separate process lets us load on mic-press, unload aggressively, and survive its death without
taking the keyboard with it.

### Gotchas

- **`RECORD_AUDIO` cannot be requested from the keyboard view.** An IME has no Activity, so the
  runtime permission prompt must be launched from a settings/onboarding Activity. Build this into
  the setup wizard (J7).
- Use `MediaRecorder.AudioSource.VOICE_RECOGNITION`, 16 kHz mono PCM — that's what Whisper expects.
- Models ship as **on-demand downloads**, not bundled in the APK, with SHA-256 verification.
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
| VAD | Silero VAD (ONNX Runtime Mobile) | |
| Decoder | Kotlin first, native if profiling demands | |
| Persistence | Room + DataStore | Clipboard, learned words, settings |
| Build | Gradle KTS, version catalog | |

**Module layout**

```
:app          setup wizard, settings UI
:ime          InputMethodService, views, touch, themes
:engine       gesture decoder, autocorrect, prediction, lexicon
:asr          Whisper service (separate process), model manager, VAD
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
