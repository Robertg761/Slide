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
benchmark (about 100 ms load and 1.7 seconds decode). The final 0.2.1 app packaging and complete
microphone-to-editor flow have not been installed or rerun on hardware in this release-hardening
pass.

**Working**
- QWERTY typing with multi-touch rollover and slide-off correction
- Long-press alternates (accents) with slide-to-select
- Shift, caps lock, auto-capitalisation, double-space period
- Backspace with auto-repeat and correct emoji/surrogate-pair deletion
- Symbols layer, editor-action-aware enter key
- Nine explicit theme presets plus Dynamic Material You; only Dynamic follows system light/dark
- Key preview popups, key borders, number row, haptics, keypress sound
- Password, email, URL, no-suggestions, and incognito field policy, with a manual no-learning mode
- A personal dictionary that learns the words and phrases you use and stops correcting them away
- Next-word prediction in the strip, from the corpus and from your own repeated phrases
- **Gesture typing** — SHARK²-derived decoder over a 160k-word lexicon, 95.8% top-1 and 100%
  top-5 on the isolated test corpus at 0.21 ms mean decode. In a sentence, where the bigram model
  can break ties the path cannot, 96.8% top-1 against 93.8% without it
- Suggestion strip showing the decoder's top three candidates, one tap to correct a miss
- Offensive-word filtering for suggestions (on by default, as in Gboard)

**Built, with hardware verification still incomplete**
- **Voice typing.** Whisper runs in a separate `:asr` process; audio never crosses the process
  boundary. The overlay, permission flow, recorder, transcriber, and native fixture benchmark are
  in place. A release-device run of the real microphone-to-editor flow is still outstanding.
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
- Clipboard and text-editing panels
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

Only the speech model is missing from a fresh clone; the lexicon and emoji catalogue are committed.
The build fetches that model once, verifies an immutable source revision and SHA-256, and packages
it in the APK. The installed app never downloads model weights.

**Speech model** (gitignored, 59,721,011 bytes):

```bash
tools/fetch_model.sh base.en-q5_1
```

The fetcher is pinned to Hugging Face revision
`5359861c739e955e79d9a303bcbc70fb988958b1` and SHA-256
`4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f`. Base is the only
packaged and selectable model; settings saved with the former Small choice migrate to Base.

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
tools/fetch_model.sh base.en-q5_1
./gradlew :app:assembleDebug
```

Gradle transitive versions are locked per module and downloaded artifacts are authenticated by
`gradle/verification-metadata.xml`. A dependency update must regenerate and review both controls;
see `docs/repository-governance.md`. Pull requests and `main` run JVM tests, release lint, an
unsigned R8 build, and the same model/package/ABI assertions used by releases.

Release APKs package `libslide_asr.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, matching
the app's Android 8 minimum rather than silently excluding supported devices.

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

`measuresEveryModel` prints load time, decode time, and speed relative to realtime for the packaged
Base model. Instrumented tests are not run by ordinary CI and were not run in this hardening pass.

## Privacy

Nothing leaves the device during typing: speech is recognised locally, and audio is held in memory
only for as long as it takes to transcribe. If the user enables update checks, Slide contacts the
public GitHub Releases API when settings opens and when **Check now** is tapped. Password, email,
URL, no-suggestions, and incognito fields are
excluded from learning. A manual Incognito mode in Slide's settings stops learning in every app
without hiding ordinary language suggestions.

Slide learns the words you use that its dictionary does not have, so it stops rewriting your own
name back at you, and the phrases you repeat, so it can offer them. Those live in
`files/learned_words.txt` and `files/learned_pairs.txt` in the app's private storage, as plain text
you can read or delete, and are excluded from cloud backup and device transfer alike —
the words a person uses that most people do not are the most revealing thing here, and they should
not leave the phone just because the phone was backed up. Hold a word in the suggestion strip to
teach it or to take it back, or use **Clear learned data** in settings to remove all learned words
and phrases from both the running keyboard and storage.
Recent emoji usage is stored under Android's no-backup directory and is not included in cloud
backup or device transfer.

## Licence and provenance

Slide's own source code and documentation are licensed under the
[Apache License 2.0](LICENSE). This permissive licence includes an explicit patent grant.

Slide clones Gboard's *functionality*, not its implementation. No Gboard code, binaries,
dictionaries, or assets are used. Dictionaries come from the Apache-2.0 AOSP wordlists; the bigram
model is derived from [Tatoeba](https://tatoeba.org) sentence data, used and redistributed under
CC BY 2.0 FR; emoji data and search keywords come from Unicode and CLDR under the Unicode licence;
Whisper weights and `whisper.cpp` are MIT. Emoji are drawn with the system font, so no glyphs are
redistributed. The packaged terms and notices, including attribution and licence links, are
available under **Licences and notices** in Slide's settings and at
`app/src/main/assets/THIRD_PARTY_NOTICES.txt`.
