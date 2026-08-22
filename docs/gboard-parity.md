# Slide — Gboard Feature Parity List

A 1:1 inventory of Gboard's feature surface, used as the master checklist for Slide.

**Tiers**
- `V1` — must ship in version 1
- `V2` — second release
- `V3` — later / nice-to-have
- `Skip` — deliberately not cloning (proprietary asset, Google-account-bound, or low value)

**Effort**: S (days) · M (1–2 weeks) · L (3–6 weeks) · XL (2+ months)

> **Scope decisions (updated 2026-08-09):** English-only v1, offline typing and speech, own gesture
> decoder. The optional GitHub update checker is the only network feature. Network-dependent typing
> features (F7 GIF search, G11 translate) remain deferred. See `technical-decisions.md` §0.

---

## A. Core typing engine

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| A1 | Tap typing on a QWERTY grid | V1 | M | Foundation. Custom `View` + Canvas. |
| A2 | Long-press key → accent/alt character popup | V1 | S | Per-layout alt-key tables. |
| A3 | Long-press-and-slide to pick from popup | V1 | S | |
| A4 | Key repeat on hold (backspace, arrows) | V1 | S | Accelerating repeat rate. |
| A5 | Shift / caps-lock (double-tap shift) | V1 | S | |
| A6 | Shift + slide to type one capital | V1 | S | |
| A7 | Symbols layout (`?123`) + second symbols page | V1 | S | |
| A8 | Optional persistent number row | V1 | S | Setting. |
| A9 | Long-press top row → digits | V1 | S | Setting. |
| A10 | Dedicated number pad layout | V2 | S | For numeric fields. |
| A11 | Spacebar swipe → cursor movement | V1 | S | Highly used; cheap to build. |
| A12 | Backspace swipe left → delete word / selection | V1 | S | |
| A13 | Touch model — Gaussian key hit correction | V1 | M | Feeds autocorrect; not just nearest-key. |
| A14 | Key press-and-hold delay setting | V2 | S | |
| A15 | Multi-touch / fast typing rollover | V1 | M | Two-finger typing must not drop keys. |
| A16 | Emoji key vs comma key (switchable) | V2 | S | The emoji key ships in the comma's slot, as in Gboard, with the comma first among the period key's alternates. The toggle is what remains. |
| A17 | Enter key adapts to field action (send/search/next/done) | V1 | S | From `EditorInfo.imeOptions`. |

---

## B. Glide / swipe typing  ← **priority #1**

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| B1 | Gesture path capture + trail rendering | V1 | S | Built; a Galaxy S24 Ultra / Android 16 visual smoke passed. Broader device coverage remains. |
| B2 | Gesture-vs-tap discrimination | V1 | S | Distance + time thresholds. |
| B3 | Word decoding from path | V1 | XL | The unreleased fail-safe and model-contract repair passed six intended injected S24 Ultra paths. Human-driven and corpus-scale device accuracy remain release gates. |
| B4 | Live word preview while still swiping | V1 | M | Built; latency and stale-result behaviour still require physical-device validation. |
| B5 | Top-3 alternates in suggestion strip after gesture | V1 | S | Built; best candidate is centre-aligned. |
| B6 | Auto-space insertion between glided words | V1 | S | |
| B7 | Glide through shift → capitalised word | V2 | S | |
| B8 | Glide to backspace → delete last glided word | V2 | S | **Built.** The next Backspace verifies and deletes the exact swipe commit, including auto-spacing, and rolls back its provisional phrase observation. |
| B9 | Glide across symbol/number keys | V3 | M | |
| B10 | Double-letter handling (no loop required) | V1 | M | Covered by model-contract regressions and an on-device `letter` glide without a loop. |
| B11 | Gesture trail colour follows theme | V1 | S | |
| B12 | Gesture "fast delete" — swipe left on delete key | V1 | S | Same as A12. |
| B13 | Gesture dynamic floating preview above finger | V2 | S | |
| B14 | Per-user gesture adaptation (learns your sloppiness) | V3 | L | |

---

## C. Voice input  ← **priority #2 (local Whisper)**

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| C1 | Mic key → voice input panel | V1 | S | **Built.** Permission, listening, transcribing, failure, and cancellation states are wired. |
| C2 | **On-device Whisper transcription** | V1 | XL | **Built.** One bundled Small English q5_1 model, complete-recording decode, bounded low-confidence retries, and runtime-selected ARM64 CPU kernels. The current microphone-to-editor flow still needs a fresh device run. |
| C3 | Model manager — browse/download/delete models | V2 | M | Deliberately not shipped: one immutable, checksummed Small model is bundled and there is no runtime model download. |
| C4 | Streaming partial results while speaking | V2 | L | Not built. Current transcription runs after Stop. |
| C5 | Automatic punctuation & casing | V1 | S | Supplied by the local Whisper decode. |
| C6 | Silence detection → auto-stop | V2 | M | Not built; current endpointing is manual with a safety recording limit. |
| C7 | Live waveform / level meter | V1 | S | **Built.** Responsive symmetric level bars, listening halo, and model/decode progress arc; device-level UX verification is still outstanding. |
| C8 | Voice commands ("delete", "send", "new line", "comma") | V2 | M | Post-processing on the transcript. |
| C9 | Continuous dictation (keeps listening) | V2 | M | Not built; each session records and decodes once. |
| C10 | Multilingual voice + language picker | V2 | M | Multilingual Whisper weights. |
| C11 | Auto language detection | V3 | S | Whisper does this natively. |
| C12 | Fallback to system `SpeechRecognizer` when no model | Skip | S | Deliberately omitted: a platform recognizer can use a network service and would violate Slide's offline speech contract. |
| C13 | Dictate into the middle of existing text | V2 | M | Cursor-aware insertion. |
| C14 | Offline typing/voice guarantee + visible status | V1 | S | Audio and transcripts stay on device. The app's separate, opt-in updater contacts GitHub. |

---

## D. Prediction, correction & language model

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| D1 | Suggestion strip (3 candidates) | V1 | M | **Built.** Carries swipe and typed candidates. |
| D2 | Autocorrect on space/punctuation | V1 | L | **Built.** Composing text + single-edit candidates over the key-proximity model. |
| D3 | Undo autocorrect (backspace right after) | V1 | S | **Built.** Verified against the field's own text before reverting. |
| D4 | Contextual correction and next-word prediction | V1 | L | **Built.** Tatoeba-derived asset, 393k pairs. Held-out synthetic nonword typo correction 84.4% -> 92.2%; swipe top-1 93.8% -> 96.8%. The strip offers the next word between words, right 26% of the time from the corpus alone. |
| D5 | Auto-capitalise sentence starts | V1 | S | |
| D6 | Double-space → period + space | V1 | S | |
| D7 | Auto-space after punctuation | V1 | S | **Built.** The next word is separated lazily, preserving punctuation runs and explicit spaces. |
| D8 | Personal dictionary (add/remove words) | V1 | M | **Built.** Hold a candidate in the strip to learn or forget it. |
| D9 | Text shortcuts / expansion (`omw` → `on my way`) | V2 | S | |
| D10 | Learn from what you type | V1 | M | **Built.** Words: committed deliberately twice, or rescued once from an autocorrect, and it is never rewritten again. Pairs: recurring phrases reweigh candidates in their own context. Both excluded from backup. |
| D11 | Contact name suggestions | V2 | M | Needs `READ_CONTACTS`; make optional. |
| D12 | Spell check + red squiggle underline | V2 | M | Via `InputConnection` spans. |
| D13 | Tap a misspelled word → correction menu | V2 | M | **Built.** Tapping into a finished word reopens it in the suggestion strip. |
| D14 | Emoji suggestions in the strip | V2 | S | |
| D15 | Block offensive words toggle | V2 | S | **Built.** Applies to swipe, typed, corpus-prediction, and personal-prediction suggestions alike. |
| D16 | Suggestion strip expand (⌄) → more candidates | V2 | S | |
| D17 | Clipboard chip suggestions in the strip | V2 | S | |
| D18 | Grammar check / proofread | Skip | — | Gboard's is a cloud LLM feature. |
| D19 | Smart Compose inline predictions | Skip | — | Cloud + app-specific. |
| D20 | Sync learned words across devices | Skip | — | Google-account-bound. |

---

## E. Layouts & languages

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| E1 | QWERTY | V1 | S | |
| E2 | QWERTZ, AZERTY, Dvorak, Colemak, PC layout | V2 | S | Data-driven layout files. |
| E3 | Layout defined by declarative files (JSON/XML) | V1 | M | Do this from day one or every layout is bespoke. |
| E4 | Multiple enabled languages at once | V2 | M | |
| E5 | Globe key to cycle languages | V2 | S | |
| E6 | Space-bar swipe to switch language | V2 | S | |
| E7 | Multilingual typing (two dictionaries live at once) | V3 | L | |
| E8 | Transliteration input (Hinglish etc.) | V3 | L | |
| E9 | CJK input (Pinyin, Zhuyin, Cangjie, Kana/flick, Hangul) | Skip (V3) | XL | Each is its own project. |
| E10 | Indic / Arabic / Cyrillic / Greek / Hebrew layouts | V3 | M | Layout data + dictionaries. |
| E11 | Per-language layout override | V3 | S | |

---

## F. Emoji, GIFs & stickers

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| F1 | Emoji picker with categories | V1 | M | **Built.** 1,914 emoji, nine categories, CLDR order. |
| F2 | Recently-used emoji | V1 | S | **Built.** First tab; skipped in incognito and password fields. |
| F3 | Emoji search by name | V1 | S | **Built.** Keyboard-backed search with recent and ranked CLDR keyword results plus an empty state. |
| F4 | Skin-tone selector | V1 | S | **Built.** Chosen from a long-press, then applied everywhere. |
| F5 | Emoji variant long-press | V1 | S | **Built.** |
| F6 | System emoji font rendering + version fallback | V1 | M | **Built.** Filtered by `Paint.hasGlyph` off the main thread, so an older font drops emoji rather than drawing tofu. |
| F7 | GIF search | V2 | M | Tenor API + key; needs network. |
| F8 | Sticker search / packs | V3 | M | |
| F9 | Emoji Kitchen (mashups) | Skip | — | Google-owned asset set. |
| F10 | Bitmoji / Minis / avatar stickers | Skip | — | Proprietary. |
| F11 | Kaomoji / text-emoticon tab | V2 | S | Cheap, well-liked. |
| F12 | Symbols tab (∆, ™, ½ …) | V2 | S | |

---

## G. Toolbar & productivity

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| G1 | Toolbar above the keys with feature shortcuts | V1 | M | **Built** as Gboard does it: clipboard and text-editing shortcuts share the suggestion strip while it has no candidates. Device pass outstanding. |
| G2 | Reorderable / customisable toolbar items | V2 | M | |
| G3 | Clipboard manager (history) | V1 | M | **Built.** Copies observed while Slide runs; recents stay in memory only, sensitive-flagged clips are never recorded. Device pass outstanding. |
| G4 | Pin clipboard items | V1 | S | **Built.** Pins persist under the no-backup directory, so clips never ride cloud backup or transfer. |
| G5 | Clipboard auto-expiry (1h like Gboard) | V1 | S | **Built.** Unpinned items are dropped an hour after they were copied. |
| G6 | Text editing panel (select/copy/cut/paste, arrows, select-all) | V1 | M | **Built.** Hold-to-repeat arrows, Select mode (Shift-held arrows), Select all, Copy, Cut, Paste, Delete. Device pass outstanding. |
| G7 | One-handed mode (shift left/right) | V2 | M | |
| G8 | Floating keyboard (drag anywhere) | V2 | L | |
| G9 | Resizable keyboard | V2 | M | |
| G10 | Split keyboard (tablets/landscape) | V3 | M | |
| G11 | Translate bar (type → live translation) | V3 | M | Requires a translation backend. |
| G12 | Google Search in keyboard | Skip | — | Google-proprietary. |
| G13 | Lens / camera text scan | Skip | — | |
| G14 | Settings shortcut on toolbar | V1 | S | |
| G15 | Theme switcher shortcut | V2 | S | |

---

## H. Themes & appearance ← **priority #3**

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| H1 | Light + dark themes | V1 | M | |
| H2 | Follow system dark mode | V1 | S | |
| H3 | Colour theme presets (Gboard's ~20 solid colours) | V1 | M | |
| H4 | Gradient theme presets | V1 | S | |
| H5 | Material You dynamic colour from wallpaper | V1 | M | API 31+; graceful fallback below. |
| H6 | Custom image background + brightness slider | V2 | M | |
| H7 | Key borders on/off | V1 | S | |
| H8 | Keyboard height adjustment | V1 | S | |
| H9 | Bottom padding / gesture-nav inset control | V1 | S | |
| H10 | Key pop-up preview on press (on/off) | V1 | S | |
| H11 | Landscape fullscreen ("extract mode") toggle | V2 | S | |
| H12 | Custom user-defined theme (pick every colour) | V2 | M | |
| H13 | Font size / key label size | V2 | S | |
| H14 | Theme applies to all panels (emoji, clipboard, voice) | V1 | M | Single design-token system — do it once, properly. |
| H15 | Edge-to-edge / rounded-corner modern styling | V1 | S | |

---

## I. Feedback

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| I1 | Haptic feedback on keypress + strength slider | V1 | S | |
| I2 | Sound on keypress + volume slider | V1 | S | |
| I3 | Respect system sound/haptic settings | V1 | S | |
| I4 | Distinct feedback for gesture start/end | V2 | S | |

---

## J. Settings, data & privacy

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| J1 | Settings app (Material 3) | V1 | M | **Built.** Setup, appearance, typing, privacy, voice, and update controls. |
| J2 | In-keyboard quick settings | V2 | S | |
| J3 | Incognito mode (no learning) | V1 | S | **Built.** Manual toggle + auto via `IME_FLAG_NO_PERSONALIZED_LEARNING` and sensitive-field policy. |
| J4 | Clear learned data | V1 | S | **Built.** Confirmed settings action clears live words/pairs and crash-safe private storage. |
| J5 | Export/import settings + dictionary | V2 | S | Local file, no cloud. |
| J6 | No telemetry / no network by default | V1 | S | **Built.** Update checks are opt-in; typing and speech do not use the network. |
| J7 | Setup wizard (enable IME, set default, grant mic) | V1 | M | Required — IME activation is a confusing flow. |

---

## K. Android platform integration

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| K1 | `InputMethodService` lifecycle done correctly | V1 | M | |
| K2 | `EditorInfo` input-type awareness (email, URL, password, numeric) | V1 | M | |
| K3 | Password/secure field — no learning, no suggestions | V1 | S | |
| K4 | Inline autofill suggestions (API 30+) | V2 | M | Password-manager chips in the strip. |
| K5 | Physical keyboard passthrough | V3 | M | |
| K6 | IME switcher (globe long-press → other keyboards) | Skip | S | Deliberately omitted: Slide is single-language, and the system picker remains available from the platform. The in-keyboard ◎ key read as a stray button next to Space. |
| K7 | RTL layout support | V2 | M | |
| K8 | Accessibility / TalkBack support | V1 | M | **Partly built.** Labels and announcements exist; device-level TalkBack and font-scale verification remain. |
| K9 | Landscape + foldable/large-screen layouts | V2 | M | |
| K10 | Per-app remembered language/layout | V3 | S | |

---

## L. Handwriting

| # | Feature | Tier | Effort | Notes |
|---|---|---|---|---|
| L1 | Handwriting input layout | V3 | XL | Needs its own recognition model. |

---

## Summary

| Tier | Count |
|---|---|
| V1 | ~62 |
| V2 | ~44 |
| V3 | ~19 |
| Skip | ~10 |

Three items carried essentially all the risk: **B3** (gesture decoder), **C2/C4** (on-device
Whisper), and **D2/D4** (autocorrect + prediction, which the gesture decoder also depends on).
Everything else is conventional Android work.

Of those, **D2** and the tap/prediction parts of **D4** are built and measured. **B3** has an
implemented neural and deterministic path, but the 0.3.0 live-use report exposed a model/search
contract bug and an empty-result failover bug; the repairs are not complete until they pass on a
physical phone. **C2** is built and its native fixture path was benchmarked on one physical phone,
but the final microphone-to-editor flow still needs a device run; **C4** streaming is not built.
