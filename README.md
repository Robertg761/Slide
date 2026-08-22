# Slide

An Android keyboard built around two things Gboard does well and no open keyboard does at all:
**gesture typing** with its own decoder, and **voice typing that runs entirely on your phone**
via a local Whisper model.

Slide is English-only. Typing and voice recognition work without a network connection; the optional
GitHub update checker is the app's only network feature.

## Downloads and updates

APK releases are published on this repository's [GitHub Releases](../../releases). Download the
latest release APK, open it on an Android device, and allow the browser or file manager to
install unknown apps when Android asks. Future APKs signed with the same release key install as
updates over this one. Slide does not use Google Play.

Update checks are off by default. If enabled in Slide's settings, the app checks public GitHub
releases (including prereleases when selected). It verifies the selected asset's published
size and SHA-256, package, newer SemVer and 64-bit Android version code, and signing certificate
when Android exposes it, then opens Android's normal installer for confirmation. Android itself
always enforces the installed app's signing identity.

## Status

Typing, themes, and gesture decoding were verified on a Galaxy S24 Ultra (Android 16). The native
Base Whisper model also loaded and transcribed an 11-second fixture on that phone in a prior
benchmark (about 100 ms load and 1.7 seconds decode). Version 0.3.4 restores runtime-selected
optimized ARM64 kernels, bounds slow low-confidence retries, and skips confidently detected edge
silence.

The complete microphone-to-editor flow was verified on that phone in August 2026 with the packaged
Small English model: cold load in about 370 ms, the 11-second fixture decoded at 1.66x realtime,
and a live dictation session committed correctly to an editor field. The on-device swipe engine's
instrumented tests passed in the same session. Early hands-on use flags three open areas: decode
latency for short utterances (about 2 seconds of fixed work per clip before low-confidence
retries), swipe accuracy during a fresh install's calibration warm-up, and voice overlay polish.

**Working**
- QWERTY typing with multi-touch rollover and slide-off correction
- Long-press alternates (accents) with slide-to-select
- Shift, caps lock, auto-capitalisation, double-space period
- Backspace with auto-repeat, correct emoji/surrogate-pair deletion, and one-tap whole-word undo
  for the immediately preceding swipe
- Symbols layer, editor-action-aware enter key
- Nine explicit theme presets plus Dynamic Material You; only Dynamic follows system light/dark
- Key preview popups, key borders, number row, haptics, keypress sound
- Password, email, URL, no-suggestions, and incognito field policy, with a manual no-learning mode
- A personal dictionary that learns the words and phrases you use and stops correcting them away
- Next-word prediction in the strip, using one- and two-word corpus context plus repeated phrases
- **Gesture typing** — an offline neural spatial model with Slide's trie-constrained beam search,
  a deterministic SHARK²-style fallback, live candidates while sliding, and correction-aware
  local phrase learning. Final inference stays off the IME thread, and one Backspace removes a
  just-swiped word plus its automatically inserted space as a single reversible action.
- Suggestion strip showing the decoder's top three candidates, one tap to correct a miss
- Offensive-word filtering for suggestions (on by default, as in Gboard)

**Built, with hardware verification still incomplete**
- **Voice typing.** Whisper runs in a separate `:asr` process; audio never crosses the process
  boundary. The bundled Small English model selects a CPU backend compatible with each ARM64 phone,
  decodes the complete recording, and retains bounded low-confidence retries. The live
  panel uses responsive speech-level bars and an explicit on-device progress animation. There is no
  network, platform-recognizer, or cloud fallback path. The real microphone-to-editor flow ran
  end to end on a physical device in August 2026 (see Status); decode latency for short utterances
  and the overlay's visual polish are the remaining gaps.
- **Autocorrect and typed-word suggestions.** The word being typed is held as composing text, so
  a correction replaces a region the editor owns rather than a character count the keyboard
  guessed at. A fast single-edit path handles ordinary slips; a trie-pruned weighted sequence
  decoder recovers bounded two-edit words. Both use actual touch positions, one- and two-word
  context, and a device-local per-key spatial model learned only from confirmed text. Candidates
  are ranked against completions of the same prefix. Backspace immediately after a correction
  puts back exactly what was typed. The single-edit path measures 0.031 ms and the gated two-edit
  fallback 2.35 ms on the JVM benchmark; the strip and autocorrection have separate switches.

  Most of the work here is in refusing to correct: a word already in the dictionary is never
  rewritten (checked against ~4,300 sampled real words), nor is a fragment that a common word
  starts with, nor anything where the runner-up candidate scores close enough to be a coin toss.
- **Emoji picker.** 1,914 emoji in nine categories, in CLDR's presentation order, reached from the
  key left of the space bar. Recently-used emoji come first, long-pressing one opens its five skin
  tones and the tone chosen becomes the default, and anything the device's font cannot draw is
  filtered out rather than shown as an empty box. It now also has a keyboard-backed search tab with
  recent results, ranked CLDR keyword matches, and an explicit empty state. Like the voice overlay
  it sits over the keys, so opening it never resizes the keyboard.
- **Interaction and accessibility polish.** The symbols layer redraws immediately when selected,
  Shift and editor-action states are visually distinct, Space moves the cursor, a leftward swipe
  from Backspace removes the preceding word, and the keyboard panels expose useful TalkBack labels.
  Emoji and footer controls use larger touch targets; the remaining work is device-level TalkBack,
  font-scale, landscape, and latency verification.

- **Clipboard and text-editing panels.** While the strip has no candidates it offers clipboard
  and edit shortcuts beside the settings gear, as Gboard's toolbar does. The clipboard panel
  shows what was copied while Slide was running — unpinned items expire after an hour and never
  leave memory, pinned items persist outside cloud backup, and clips a source marks sensitive
  are never recorded. The editing panel has hold-to-repeat arrows, a Select mode that makes the
  arrows extend the selection, Select all, Copy, Cut, Paste, and Delete, all driven through the
  editor's own key handling. Neither has had a physical-device pass yet.

**Not yet built**
- Remaining appearance settings, setup-wizard polish, and full accessibility/adaptive-layout QA

## Documentation

| Document | Contents |
|---|---|
| `docs/gboard-parity.md` | Full Gboard feature inventory, tiered V1/V2/V3/Skip |
| `docs/technical-decisions.md` | Gesture decoder design, Whisper model choice, stack, risks |
| `docs/repository-governance.md` | Required GitHub rules, signing boundary, and release procedure |

## Modules

```
:app      setup wizard, settings UI (Compose + Material 3)
:core     layout schema, theme tokens, settings store, emoji catalogue
:engine   lexicon, gesture decoding, typing suggestions and autocorrect
:ime      InputMethodService, key rendering, touch and gesture capture, voice overlay
:asr      whisper.cpp via JNI, audio capture, out-of-process voice service
```

`:asr` runs in its own process (`:asr`). Isolating the model and native inference state means a
low-memory kill can stop dictation without taking the keyboard down in every app.

## Assets

The speech and neural swipe weights are missing from a fresh clone; generated language assets are
committed. The build fetches weights once from immutable revisions, verifies SHA-256, and packages
them in the APK. The installed app never downloads model weights.

**Swipe models** (gitignored):

```bash
tools/fetch_executorch.sh
tools/fetch_swipe_models.sh
```

The model terms are packaged with the app and gesture typing is visibly attributed to FUTO Swipe
in settings. Slide's trie beam search, context integration, fallback, and input handling are its
own Apache-2.0 implementation.

**Speech model** (gitignored, 190,098,681 bytes):

```bash
tools/fetch_model.sh small.en-q5_1
```

The fetcher is pinned to Hugging Face revision
`5359861c739e955e79d9a303bcbc70fb988958b1` and SHA-256
`bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30`. Small English is the
only packaged model. Slide accepts the larger install so conversational microphone input does not
depend on Whisper Base's much narrower accuracy margin.

**whisper.cpp** is vendored under `third_party/whisper.cpp` at a pinned commit, stripped of
bindings, examples, tests, and every backend Android cannot use. To refresh it:

```bash
tools/vendor_whisper.sh
```

The pinned commit lives in `tools/vendor_whisper.sh`; the vendored copy records what it was
built from in `third_party/whisper.cpp/VENDORED_COMMIT`.

**Generated language data** is committed, but every input is locked by byte length and SHA-256 in
`tools/language_sources.json`. Unicode is fixed at 17.0; CLDR and the LineageOS copy of AOSP
LatinIME are fixed at full Git commits. Neither `latest`, `main`, nor a mutable branch is accepted.

The lexicon comes from that locked AOSP wordlist. The context models let autocorrect read the
sentence rather than guess from spelling — "at ocne" reaches "once" because the corpus knows what
follows "at". They use the locked 2026-08-08 Tatoeba English snapshot and are keyed by lexicon
index, so the lexicon, bigrams, trigrams, and held-out evaluation sample rebuild as one unit. A
tenth of the corpus is held back by sentence id and never trains the model.

Tatoeba publishes its per-language export at a moving URL. Slide therefore preserves the exact
authenticated compressed snapshot under `third_party/language-data`; the URL is only an origin
record and refresh hint. The recorded size and SHA-256 remain authoritative, and a newer export is
never silently substituted.

The emoji catalogue uses the locked Unicode file and CLDR English annotations. Missing, malformed,
or wrong-revision annotations abort the build rather than silently producing name-only search.

To download the locked inputs and prove all five committed outputs reproduce byte-for-byte:

```bash
python3 tools/fetch_language_sources.py --output-dir /tmp/slide-language-sources
python3 tools/rebuild_language_assets.py \
    --sources-dir /tmp/slide-language-sources --check
python3 tools/test_language_sources.py
./gradlew :core:testDebugUnitTest :engine:testDebugUnitTest
```

Use `--write` instead of `--check` only when intentionally replacing all generated outputs. See
[`docs/data-provenance.md`](docs/data-provenance.md) for the exact revisions, hashes, and refresh
procedure. New Unicode emoji still depend on the device font; unsupported glyphs are filtered out.

## Building

Requires JDK 17 — the Android Gradle Plugin does not support JDK 25, which is this machine's
default. The `:asr` native build also needs the NDK and CMake, both installable from the SDK
manager.

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk
tools/prepare_assets.sh
./gradlew :app:assembleDebug
```

Gradle transitive versions are locked per module and downloaded artifacts are authenticated by
`gradle/verification-metadata.xml`. A dependency update must regenerate and review both controls;
see `docs/repository-governance.md`. Pull requests and `main` run JVM tests, release lint, an
unsigned R8 build, final model/package/native-provenance assertions, and packaged-runtime tests on
Android API 26 and API 37 emulators. Tagged releases additionally compare the reviewed APK with an
independent Git source-export rebuild. Signed releases carry a SHA-256 file, CycloneDX SBOM, R8
mapping, native symbols, and GitHub build-provenance and SBOM attestations.

Release APKs package `libslide_asr.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, matching
the app's Android 8 minimum rather than silently excluding supported devices.

Install and enable:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open Slide and follow the two setup steps — enable it in the system keyboard list, then pick
it from the input-method switcher.

### Instrumented tests

The speech and neural swipe tests need a device because they load and execute the packaged models:

```bash
./gradlew :asr:connectedDebugAndroidTest :engine:connectedDebugAndroidTest
```

`measuresEveryModel` prints load time, decode time, and speed relative to realtime for the packaged
Base model. CI and release workflows run both suites on isolated API 26 and API 37 emulators through
`tools/run_android_instrumentation.sh`; emulator coverage is still not physical-device signoff.

## Privacy

Nothing leaves the device during typing: speech is recognised locally, and audio is held in memory
only for as long as it takes to transcribe. If the user enables update checks, Slide contacts the
public GitHub Releases API when settings opens with checks enabled, when checks are enabled or the
prerelease preference changes, and when **Check now** is tapped. Password, email,
URL, no-suggestions, and incognito fields are
excluded from learning. A manual Incognito mode in Slide's settings stops learning in every app
without hiding ordinary language suggestions.

Slide learns the words you use that its dictionary does not have, so it stops rewriting your own
name back at you, and the phrases you repeat, so it can offer them. Those live in
`files/learned_words.txt` and `files/learned_pairs.txt` in the app's private storage, as plain text
you can read or delete, and are excluded from cloud backup and device transfer alike —
the words a person uses that most people do not are the most revealing thing here, and they should
not leave the phone just because the phone was backed up. Hold a word in the suggestion strip to
teach it or to take it back, or use **Clear learned data** in settings to remove learned words,
phrases, and per-key touch calibration from both the running keyboard and storage.
Recent emoji usage is stored under Android's no-backup directory and is not included in cloud
backup or device transfer. Per-key touch calibration and its temporary files are also excluded;
ordinary keyboard preferences use Android backup and device transfer so a new device keeps the
chosen layout and appearance without receiving the old device's personalised touch model.

## Licence and provenance

Slide's own source code and documentation are licensed under the
[Apache License 2.0](LICENSE). This permissive licence includes an explicit patent grant.

Slide aims for Gboard-class *functionality*, not its implementation. It has not reached feature,
accuracy, or polish parity yet. No Gboard code, binaries,
dictionaries, or assets are used. Dictionaries come from the Apache-2.0 AOSP wordlists; context
models are derived from [Tatoeba](https://tatoeba.org) sentence data, used and redistributed under
CC BY 2.0 FR; emoji data and search keywords come from Unicode and CLDR under the Unicode licence;
Whisper weights and `whisper.cpp` are MIT. Emoji are drawn with the system font, so no glyphs are
redistributed. The packaged terms and notices, including attribution and licence links, are
available under **Licences and notices** in Slide's settings and at
`app/src/main/assets/THIRD_PARTY_NOTICES.txt`.
