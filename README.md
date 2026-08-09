# Slide

An Android keyboard built around two things Gboard does well and no open keyboard does at all:
**gesture typing** with its own decoder, and **voice typing that runs entirely on your phone**
via local Whisper models.

English-only and fully offline for v1.

## Downloads and updates

APK releases are published on this repository's [GitHub Releases](../../releases). Download the
latest release APK, open it on an Android device, and allow the browser or file manager to
install unknown apps when Android asks. Future APKs signed with the same release key install as
updates over this one. Slide does not use Google Play.

Update checks are off by default. If enabled in Slide's settings, the app checks public GitHub
releases (including alpha prereleases when selected), verifies the downloaded APK's version and signing
certificate, then opens Android's normal installer for your confirmation.

## Status

Typing, themes and gesture decoding are verified on a Galaxy S24 Ultra (Android 16). Voice input
is written and compiles but has **not yet run on hardware** — see *Not yet verified* below.

**Working**
- QWERTY typing with multi-touch rollover and slide-off correction
- Long-press alternates (accents) with slide-to-select
- Shift, caps lock, auto-capitalisation, double-space period
- Backspace with auto-repeat and correct emoji/surrogate-pair deletion
- Symbols layer, editor-action-aware enter key
- Nine themes plus Material You dynamic colour, light/dark following the system
- Key preview popups, key borders, number row, haptics, keypress sound
- Password and incognito field detection (no learning in those fields)
- A personal dictionary that learns the words you use and stops correcting them away
- **Gesture typing** — SHARK²-derived decoder over a 160k-word lexicon, 95.8% top-1 and 100%
  top-5 on the isolated test corpus at 0.21 ms mean decode. In a sentence, where the bigram model
  can break ties the path cannot, 96.8% top-1 against 93.8% without it
- Suggestion strip showing the decoder's top three candidates, one tap to correct a miss
- Offensive-word filtering for suggestions (on by default, as in Gboard)

**Built, not yet verified on hardware**
- **Voice typing.** Whisper runs in a separate `:asr` process; audio never crosses the process
  boundary. The overlay, permission flow, recorder and transcriber are all in place. What is
  outstanding is a device run — including the benchmark that picks the default model.
- **Autocorrect and typed-word suggestions.** The word being typed is held as composing text, so
  a correction replaces a region the editor owns rather than a character count the keyboard
  guessed at. Corrections are generated as single edits over a key-proximity model — transposition,
  neighbouring-key substitution, doubled and dropped letters, missing apostrophes — and ranked
  against completions of the same prefix. Backspace immediately after a correction puts back
  exactly what was typed. 0.031 ms per keystroke, and both the strip and autocorrection have
  their own settings switches.

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

**Not yet built**
- Next-word *prediction* in the strip. The bigram model exists and is wired into correction and
  gesture decoding; what is missing is offering a word before anything has been typed.
- Adaptive bigrams. The personal dictionary learns words, not yet the pairs they appear in.
- Clipboard and text-editing panels
- Remaining appearance settings, setup-wizard polish, and full accessibility/adaptive-layout QA

## Documentation

| Document | Contents |
|---|---|
| `docs/gboard-parity.md` | Full Gboard feature inventory, tiered V1/V2/V3/Skip |
| `docs/technical-decisions.md` | Gesture decoder design, Whisper model choice, stack, risks |

## Modules

```
:app      setup wizard, settings UI (Compose + Material 3)
:core     layout schema, theme tokens, settings store, emoji catalogue
:engine   lexicon, gesture decoding, typing suggestions and autocorrect
:ime      InputMethodService, key rendering, touch and gesture capture, voice overlay
:asr      whisper.cpp via JNI, audio capture, out-of-process voice service
```

`:asr` runs in its own process (`:asr`). A 182 MB model that gets OOM-killed then takes the
keyboard down with it would make the keyboard unusable in every app on the phone; isolating it
means the worst case is dictation failing while typing carries on.

## Assets

Only the speech models are missing from a fresh clone; the lexicon and the emoji catalogue are
committed, so everything except voice typing builds with no network.

**Speech models** (gitignored — hundreds of megabytes):

```bash
tools/fetch_model.sh base.en-q5_1     # 57 MB
tools/fetch_model.sh small.en-q5_1    # 182 MB
```

Both are currently bundled so the on-device benchmark can compare them; once
`WhisperModel.Default` is settled by measurement, the loser is dropped and the APK shrinks by
roughly its size.

**whisper.cpp** is vendored under `third_party/whisper.cpp` at a pinned commit, stripped of
bindings, examples, tests, and every backend Android cannot use. To refresh it:

```bash
tools/vendor_whisper.sh
```

The pinned commit lives in `tools/vendor_whisper.sh`; the vendored copy records what it was
built from in `third_party/whisper.cpp/VENDORED_COMMIT`.

**The lexicon** is generated from the AOSP wordlist and committed, so it needs no network. To
regenerate it:

```bash
curl -sL -o /tmp/aosp_en.gz https://raw.githubusercontent.com/LineageOS/android_packages_inputmethods_LatinIME/lineage-21.0/dictionaries/en_wordlist.combined.gz
gunzip -c /tmp/aosp_en.gz > /tmp/aosp_en.txt
python3 tools/build_lexicon.py /tmp/aosp_en.txt engine/src/main/assets/lexicon_en.bin
./gradlew :engine:testDebugUnitTest
```

**The bigram model** is what lets autocorrect read the sentence rather than guess from spelling —
"at ocne" reaches "once" because the corpus knows what follows "at". It is generated from
Tatoeba's English sentence export and committed alongside the lexicon, which it is keyed against
by index, so it must be rebuilt whenever the lexicon is:

```bash
curl -sL -o /tmp/tatoeba.tsv.bz2 https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
bunzip2 -kf /tmp/tatoeba.tsv.bz2
python3 tools/build_bigrams.py /tmp/tatoeba.tsv \
    engine/src/main/assets/lexicon_en.bin \
    engine/src/main/assets/bigrams_en.bin \
    engine/src/test/resources/heldout_en.txt
./gradlew :engine:testDebugUnitTest
```

A tenth of the corpus is held back by sentence id and written to `heldout_en.txt`, which the model
is never trained on. `ContextualCorrectionTest` measures against those sentences, so the numbers it
reports are not the model marking its own homework.

**The emoji catalogue** is generated from Unicode's `emoji-test.txt` and CLDR's English
annotations, and is likewise committed. The script downloads its own sources:

```bash
python3 tools/build_emoji.py
./gradlew :core:testDebugUnitTest
```

Rebuilding it against a newer Unicode release is how new emoji arrive. Nothing else needs to
change: the panel filters out whatever the device's font cannot draw.

## Building

Requires JDK 17 — the Android Gradle Plugin does not support JDK 25, which is this machine's
default. The `:asr` native build also needs the NDK and CMake, both installable from the SDK
manager.

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk
./gradlew :app:assembleDebug
```

Only `arm64-v8a` is built. Every phone this targets is arm64, and the other ABIs would multiply
native build time for nothing.

Install and enable:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open Slide and follow the two setup steps — enable it in the system keyboard list, then pick
it from the input-method switcher.

### Instrumented tests

The speech tests need a connected device, since they load and run the real model:

```bash
./gradlew :asr:connectedDebugAndroidTest
```

`measuresEveryModel` prints load time, decode time and speed relative to realtime for each
packaged model. That output is what decides `WhisperModel.Default`.

## Privacy

Nothing leaves the device during typing: speech is recognised locally, and audio is held in memory
only for as long as it takes to transcribe. The optional update check contacts GitHub only when the
user enables it when Slide opens. Password and
incognito fields are excluded from any learning.

Slide learns the words you use that its dictionary does not have, so it stops rewriting your own
name back at you. That list lives in `files/learned_words.txt` in the app's private storage, as
plain text you can read or delete, and is excluded from cloud backup and device transfer alike —
the words a person uses that most people do not are the most revealing thing here, and they should
not leave the phone just because the phone was backed up. Hold a word in the suggestion strip to
teach it or to take it back.

## Licence and provenance

Slide clones Gboard's *functionality*, not its implementation. No Gboard code, binaries,
dictionaries, or assets are used. Dictionaries come from the Apache-2.0 AOSP wordlists; the bigram
model is derived from [Tatoeba](https://tatoeba.org) sentence data, used and redistributed under
CC BY 2.0 FR; emoji data and search keywords come from Unicode and CLDR under the Unicode licence;
Whisper weights and `whisper.cpp` are MIT. Emoji are drawn with the system font, so no glyphs are
redistributed.
