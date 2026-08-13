# Changelog

## Unreleased

## [0.3.4] - 2026-08-13

### Changed

- Offline voice transcription now selects the fastest compatible ARM64 Whisper kernel at runtime
  instead of forcing every phone through the portable Armv8 baseline. Newer devices can use
  dot-product, FP16, i8mm, SVE, or SME kernels while older supported phones retain a safe baseline.
- Conservative content-local trimming removes only confident leading and trailing silence before
  inference. Clean greedy decoding is unchanged, while low-confidence retries are bounded to avoid
  a combinatorial latency tail without removing Whisper's accuracy fallback.
- The voice panel now uses responsive level bars with fast attack and smooth release, a pulsing mic
  halo while listening, and a rotating progress arc while the model loads or transcribes. Its copy
  explicitly identifies listening and transcription as offline/on-device.

### Privacy

- Dictation remains fully offline: the speech module has no network permission, URL client, Android
  platform recognizer, runtime model downloader, telemetry, or cloud fallback. Raw audio remains in
  the isolated speech process and is wiped after each decode.

### Verification

- JVM tests cover silence trimming, level dynamics, bounded decode configuration, runtime ARM64
  backend selection, and the offline-only speech boundary. The full release gate, packaged native
  fixture, clean-export reproduction, and hosted APK checks were run for the tagged update.
- No physical Android device was connected. The ARM64 dispatch and real microphone/animation feel
  are structurally verified but not newly measured on phone hardware.

## [0.3.3] - 2026-08-13

### Added

- Swipe typing now learns from verified suggestion-strip replacements and repeated immediate
  whole-word undo. The bounded preference model reranks both neural and deterministic decoder
  results, decays old evidence, and leaves unrelated candidate lists unchanged.
- A process-local typing quality snapshot reports coarse latency, confidence, candidate-count,
  outcome, decoder-source, correction, and model-readiness totals through Android's permission-
  gated service dump. It stores no typed text, gesture coordinates, editor identity, or event rows.
- The opt-in typing quality reporter compares content-free JSONL benchmark outcomes, including
  top-1 and top-k accuracy, wrong commits, abstentions, fallback use, latency percentiles,
  confidence calibration, before-and-after deltas, and explicit regression budgets.

### Changed

- Neural swipe failover now reports which decoder produced each result instead of requiring the IME
  to infer provenance from model availability.
- Personal swipe preferences persist as salted word fingerprints with bounded strengths and logical
  ages. Incognito fields do not train the model, and clear-personalized-data removes the snapshot in
  the same fail-closed transaction as learned words, phrases, and touch calibration.
- Gesture preference files and temporary residues are excluded from legacy backup, cloud backup,
  and device transfer.

### Verification

- JVM tests for adaptation, persistence, neural failover, IME wiring, concurrent aggregate
  collection, backup rules, and the JSONL writer pass. The Python report suite passes with Ruff,
  release lint, debug and minified release assembly, Android-test compilation, strict APK checks,
  release-script contracts, and exact locked-language regeneration.
- No Android device was connected for this release pass, and the optional donated real-swipe corpus
  was not downloaded. Physical typing feel and corpus-level improvement remain unmeasured.

## [0.3.2] - 2026-08-12

### Fixed

- Reopening a finished word now respects an editor that rejects `setComposingRegion`, and ordinary
  editor mutations update Slide's composing, learning, and autocorrect state only after the
  corresponding `InputConnection` operation succeeds. Raw `TYPE_NULL` editors receive key events
  for typing, Backspace, and Enter.
- Swipe and dictation spacing now use shared, code-point-aware context for Unicode punctuation,
  paired quotes, brackets, ellipsis, and emoji. Sentence and word context recognises smart
  apostrophes and Unicode sentence/paragraph boundaries instead of learning across them.
- The emoji grid exposes accessibility scroll actions and preserves virtual focus as it scrolls;
  tone selection can be cancelled vertically, and taps or drags in distant padding no longer
  commit the nearest key.
- Native Whisper transcripts cross JNI as ordinary UTF-8 bytes, so supplementary Unicode such as
  emoji is decoded correctly instead of being handed to JNI's incompatible Modified UTF-8 API.
- Leaving or cancelling an update download now cancels the process-owned job and prevents Package
  Installer from appearing over another app. Certificate rotation history is accepted without
  accepting an unrelated signer.
- Clearing learned words, phrases, and touch calibration now fsyncs every affected directory before
  removing its fail-closed deletion marker. Personal touch offsets and residues are excluded from
  backup and transfer, while ordinary settings migrate to a backup-eligible, privacy-filtered store.
- Lexicon and n-gram loaders reject noncanonical, unsorted, out-of-range, truncated, trailing, or
  otherwise malformed assets instead of silently mis-scoring corrupt data.
- The patched ExecuTorch runtime no longer contributes a test instrumentation declaration to the
  release manifest, and native builds identify the tracked whisper.cpp snapshot rather than an
  ambient or stale Slide Git revision.
- Slide now honours Android's IME-switching contract with a working globe key, and hides voice input
  on devices without microphone hardware.
- Neural swipe ranking now applies the model's calibrated language weight to the lexicon's raw
  `0..255` frequency value. The previous extra logarithm almost erased the language prior; this
  removes a concrete scoring cause of everyday contractions losing to much less common names.
- One Backspace immediately after a swipe now removes the complete committed word as a unit,
  including Slide's automatic leading space and the word's casing. The deletion is verified
  against the editor's actual text and also rolls back the provisional learned phrase.
- The packaged-runtime emulator runner now defaults to Lavapipe and an 8 GiB data partition. This
  avoids emulator 37.1.11's SwiftShader initialization crash and Android 37's first-boot storage
  exhaustion. Headless runs disable SurfaceFlinger's unrelated luma sampling to avoid a current
  `mapper.ranchu` readback assertion, and the runner requires stable package, activity, and settings
  services before tests.

### Changed

- The app now targets Android API 37 as version 0.3.2 (`versionCode` 11). Fresh builds prepare all
  speech, swipe, and ExecuTorch assets through one checked entry point.
- CI and release workflows require packaged speech/swipe instrumentation on API 26 and API 37.
  Tagged builds also require an independent source-export rebuild, exact runtime-asset and native
  provenance checks, a CycloneDX SBOM, checksum, R8 mapping, native symbols, and signed GitHub
  provenance/SBOM attestations.
- Unicode, CLDR, and AOSP language inputs are now locked to immutable releases/commits and exact
  hashes. The exact Tatoeba snapshot is preserved in-tree because its official URL rotates, and
  the complete emoji/lexicon/context rebuild fails closed unless all inputs and outputs match.
- Finger-up decoding runs away from the IME thread. Completed swipes and immediately following
  keys, cursor gestures, panel requests, or second swipes are applied in release order, while
  field and gesture-setting invalidation cancels the sequence. A live preview still in native
  inference can no longer freeze key rendering while the final decode waits for it.
- The starting key no longer remains visually pressed throughout a swipe. The trail has a clearer
  fingertip head, fades briefly after lift, starts previews only after meaningful travel, and
  identical preview candidates no longer redraw the suggestion strip.

### Verification

- Full JVM suites for all five modules pass, together with module lint, the minified release build,
  the strict release-APK verifier, release-script contracts, and CycloneDX schema validation.
- Packaged runtime instrumentation passes on API 26: Engine 2/2 and ASR 5/5, including the
  bundled models and a regression for digitally silent PCM input.
- The deterministic fallback was measured on 20,000 donated real-finger QWERTY traces as a
  diagnostic. Its 50.23% top-1 result confirms it must remain failover rather than broadly
  overriding neural output.
- The API 37 preview system image could not reach app tests on this host because its ranchu graphics
  stack repeatedly aborted SurfaceFlinger. The reported `that's` trace has not yet been replayed
  through the packaged neural model, and physical-phone validation remains pending.

## [0.3.1] - 2026-08-10

### Fixed

- A neural swipe that produced no terminal candidate now runs the deterministic decoder instead
  of falling through to the gesture's first key, so the optional model can no longer make basic
  glide typing less reliable.
- Neural beam search now follows the packaged model's Viterbi state and repeated-letter contract;
  words such as `letter` no longer require an artificial blank or finger loop between identical
  letters.
- Neural inference must decode the publisher's known trace during keyboard startup or it is
  disabled before the first user gesture; loading model files alone is no longer considered ready.
- The deterministic decoder becomes available before native model loading begins, so first-use
  swipes no longer behave like key slide-off while model files are copied and initialized.
- A deliberate full-length swipe with no candidate now commits nothing instead of inserting the
  first key under the finger; short press-wanders still resolve to the intended tap.
- A one-letter neural result can no longer suppress deterministic recovery for a real gesture;
  genuine one-letter input remains a tap rather than a glide.

### Changed

- The default borderless keyboard no longer draws a separate tile behind every letter, while
  action keys retain their stronger surfaces. Keycaps use more consistent rounding and spacing.
- The best suggestion occupies the centre cell, the idle strip stays visually quiet, the space
  bar uses a user-facing language label, and the gesture trail is continuous and smoothed.
- The suggestion strip now has an unmistakable gear control with full touch and accessibility
  targets. It opens a themed, scrollable preferences panel inside the keyboard window, and editor
  transitions always close that panel so another app cannot inherit a stale settings surface.
- Key previews stay within the keyboard width, alternate-character selection cancels cleanly when
  the finger leaves its interaction corridor, and popup surfaces use consistent rounding.
- Swipe-time drawing reuses action-icon paths, popup geometry, and preview backgrounds instead of
  allocating replacement objects during interaction.
- The emoji search tab now uses a theme-colored geometric icon instead of a device-font glyph that
  rendered nearly black on Samsung's dark keyboard; its footer icon path is reused as well.
- Starting a word after comma, sentence punctuation, colon, semicolon, or ellipsis now inserts the
  missing space without doubling an explicit space or breaking punctuation runs such as `?!`.
- Long-press menus are constrained to the phone width, and the alpha layout exposes the common
  symbols shown in its key hints without requiring a trip to the symbols page.
- Settings now expose keyboard height, gesture-navigation padding, haptic strength, keypress
  volume, auto-capitalization, and double-space punctuation instead of hiding supported controls.
- Shift, backspace, microphone, search, and emoji now use one compact optical size and rounded
  stroke system; the shift arrow no longer resembles a house and emoji eyes render as solid dots.

### Verification

- A Galaxy S24 Ultra on Android 16 passed neural startup health, six intended injected glides
  (`computer`, `keyboard`, `letter`, `perfect`, `swipe`, and `hello`), `dont` / `doesnt`
  autocorrection, settings navigation, symbol and emoji layers, and screenshot review. Post-lift
  commit was observed within 91 ms using coarse ADB markers.
- A follow-up S24 Ultra pass verified `hello. world` auto-spacing from literal key taps, an
  edge-bounded 11-choice `e` popup, punctuation hints with and without the number row, the new
  settings controls, and continued multi-point gesture decoding.
- Broader human-driven testing across varied speeds, angles, apps, and a recorded trace corpus is
  still required before claiming general accuracy parity or publishing another release.

## [0.3.0] - 2026-08-10

### Added

- Gesture typing now uses the offline FUTO Swipe neural spatial model on supported 64-bit Android
  devices, followed by Slide's own trie-constrained CTC beam search. The existing deterministic
  decoder remains available automatically if neural inference cannot start.
- The suggestion strip shows coalesced partial candidates while a swipe is still in progress,
  without allowing preview inference to queue behind newer finger positions.
- Typed correction can recover bounded two-edit mistakes through a trie-pruned weighted sequence
  search. The fast single-edit path remains first, so ordinary key slips keep their existing
  latency.
- A trigram language model adds the word before the immediately preceding word to tap, swipe, and
  next-word ranking. On 16,139 held-out typo cases it bought 194 additional correct corrections
  for 14 additional wrong ones over bigram context alone.
- Slide learns a private per-key spatial calibration from confirmed typing and correction choices.
  It is stored under Android's no-backup directory and is removed by **Clear learned data**.

### Changed

- Swipe traces now retain Android's historical samples and event timing, are resampled at a stable
  cadence, and are normalised against the active keyboard layout before neural inference.
- Choosing a different swipe candidate repairs the learned phrase evidence for the rejected and
  selected words. Undoing an autocorrection likewise repairs phrase evidence and learns the
  confirmed touch alignment rather than the rejected correction.
- Release and CI builds reproducibly prepare a pinned ExecuTorch Android runtime, fetch immutable
  swipe-model revisions, verify their SHA-256 digests, and package the model licence and visible
  FUTO Swipe attribution. Release verification now checks the swipe models, trigram, native
  runtime ABIs, compression methods, and exact hashes in the final APK.

### Fixed

- A corrupt or interrupted copied swipe-model file is atomically replaced instead of being reused
  on the next keyboard start.
- Neural runtime failures disable repeated retries for that keyboard process and fall back cleanly;
  shutdown now waits for any active decode before releasing native modules.
- Live swipe previews are cancelled when the gesture, field, or keyboard session becomes stale, so
  a late partial result cannot replace the final candidates.

### Known limitations

- Neural swipe inference is packaged for arm64-v8a and x86_64. The deterministic decoder is used on
  32-bit Android ABIs.
- The neural instrumentation test and release APK were built successfully, but this release's
  neural accuracy and latency have not yet been measured on a physical Android device.

## [0.2.1] - 2026-08-10

### Added

- Privacy controls now include a manual Incognito mode and a confirmed **Clear learned data**
  action for both personal words and repeated phrases.
- Third-party software and language-data notices are packaged in the APK and readable from the
  settings screen.
- Slide's first-party source code and documentation are now explicitly licensed under Apache-2.0.
- Pull requests and `main` now have a JVM-test, lint, and release-packaging workflow. Dependency
  lockfiles, artifact checksums, pinned Actions, Dependabot, and documented repository controls
  make dependency and release changes reviewable.
- The strip offers the next word between words, where it used to sit empty. Continuations come
  from the corpus and from your own repeated phrases, with yours first — measured against held-out
  text, the next word is one tap away about a quarter of the time from the corpus alone. It stays
  quiet when it has nothing confident to say, because three guessed words cost a glance every time
  they appear.
- Corrections use where your finger actually landed, not just which key it registered. A touch
  that caught the right-hand edge of "s" is strong evidence for "d"; that d is next to s is equally
  true of every "s" ever typed. On simulated typing where mis-hits emerge from the geometry rather
  than being written in, this corrects 5.7 more points of them at ordinary accuracy and slightly
  fewer wrongly. Words typed accurately are untouched.
- Slide learns your phrases as well as your words. The pairs you actually write — "kubectl apply",
  a friend's first name and surname — weigh candidates alongside the corpus model, in the context
  they were learned in and nowhere else. A pair has to recur several times before it counts for
  anything: measured on held-out text, acting on a pair seen once cost about twice as many wrong
  corrections as it bought right ones.
- Slide learns your words. A word committed deliberately twice — or rescued once from an
  autocorrect, which is the clearest signal a keyboard ever gets — stops being corrected away, and
  starts being offered as a completion. This is the gap that made a keyboard feel like it was
  fighting you: names, slang and jargon were rewritten every single time and never suggested once.
  Hold a candidate in the suggestion strip to teach a word or take one back. Nothing is learned in
  password or incognito fields, the list lives in the app's private storage as plain text you can
  read or delete, and it is excluded from cloud backup and device transfer.
- Swipe reads the sentence too. The same model weighs each decoded candidate by how well it
  follows the word before it, which lifts top-1 accuracy on traced held-out sentences from 93.8% to
  96.8% — nearly halving the error rate. It is the only thing that can separate words tracing an
  identical path: "typing" and "topping" are the same gesture, because y and o both lie between t
  and p, and no amount of geometry will ever tell them apart. Of 65 such cases in the sample, 40
  now resolve.
- Autocorrect reads the sentence. A bigram language model, built from Tatoeba's English sentence
  corpus and shipped as a 1.3 MB asset, weighs each candidate by how well it follows the word
  before it. On held-out sentences the model was never trained on, with synthetic nonword
  single-edit typos, this takes correction from 84.4% to 92.2% while barely moving wrong
  corrections. The cases it resolves — "at ocne" to "once", "my hroat" to "throat", "without
  efort" to "effort" — are
  exactly the ambiguous ones a wrong correction used to come from. Words the model has never seen
  in sequence are left exactly where spelling alone put them, so its gaps cost nothing.
- A second symbols page behind `=\<`, carrying the slash, equals sign, brackets, angle brackets,
  pipe, caret, and currency symbols. The key previously asked for the page it was already on, so
  nothing happened when it was pressed.
- Tapping into a word that is already typed reopens it, so the suggestion strip offers replacements
  for it. Fixing a wrong swipe or a missed correction no longer means deleting back to it.

### Changed

- Slide now packages only the 57 MB Base English Whisper model. It was the faster measured option
  on the test phone, and dropping Small removes about 181 MB from every install and update.
- The release build now enables code and resource shrinking. The Whisper model remains stored
  uncompressed so native code can map it directly without allocating a second copy.
- Release builds are unsigned and unprivileged in Gradle. An approval-gated job signs the exact
  verified artifact without checking out or running repository code while the key is available,
  and a separate least-privilege job publishes it.
- Recent emoji usage now lives under Android's no-backup storage and is migrated out of the legacy
  settings file. Personal words and phrases remain excluded from backup and device transfer.

- Autocorrect now fires on 87.7% of generated nonword single-edit typos, up from 76.6%, with wrong
  corrections at 0.6%. This benchmark excludes valid-word collisions and final-letter omissions,
  which the keyboard deliberately refuses to rewrite. The confidence margin was measured on a
  scale it did not fit: because the language score is a log of an already-logarithmic frequency,
  every word worth correcting to sits
  in a band about a quarter wide, and the old margin spent more than half of it. Typos of common
  words — "htis", "thjs", "drom", "witth" — had the right answer in the strip and were refused.
- Restoring a dropped letter is now cheaper than overriding a key you pressed. An insertion only
  adds to what was typed, where a substitution asserts that a key you did press was not the one you
  wanted, so the edit that contradicts less of the input should be the cheaper explanation — and at
  0.6 against a substitution's 0.5 it was the dearer one. That is how "sould" reached "would"
  instead of "should", and "fom" reached "tom" instead of "from". Dropped-letter typos go from
  66.4% corrected to 78.1%, and wrong corrections *halve*, 1.5% to 0.7%.
- A doubled letter is now priced as the mechanical slip it is rather than as an ordinary stray
  keystroke, so "largee" no longer becomes "larger" or "sidde" "sided". Nothing in that class is
  rewritten to a different word any more, where the corrector previously preferred changing the
  word to un-doubling the key.
- Backspace taps commit on release unless key repeat has started, so sliding into delete-word or a
  selection can cancel the tap cleanly; hold repeat still accelerates quickly.
- The navigation-bar strip below the keys is painted in the keyboard's own background colour, so
  the keyboard meets the bottom of the screen without a black band and a visible seam.
- Swipes are reconstructed from every touch sample Android batches into a move event rather than
  only the newest, which gives the decoder the path the finger actually took on a fast swipe.
- A swipe too short for the decoder to read now types the key it started on instead of committing
  nothing.

### Fixed

- Voice commands and callbacks now carry a session ID, so a canceled native decode cannot hide,
  reset, or commit into a replacement dictation session. Binder-failure cleanup is confined to the
  service main thread, and editor changes still invalidate late transcripts before they can reach a
  new field.
- Audio capture now has per-recording ownership and cleanup. The two-minute limit transcribes the
  captured prefix instead of discarding it, a slow driver cannot leak samples or errors into the
  next recording, `AudioRecord` is released exactly once, and used PCM buffers are erased after
  success, cancellation, and failure. Vendor microphone shutdown no longer runs on the service main
  thread, so its bounded teardown cannot turn into an input-service ANR before the timeout begins.
- The ASR process closes its native Whisper context when the service is destroyed, can abort an
  in-flight decode, and converts native allocation failures into ordinary transcription errors.
  Native libraries now cover all four Android ABIs and use the Armv8.0 baseline instead of
  unconditionally executing Armv8.2 dot-product instructions.
- Update selection now compares every eligible GitHub release using complete SemVer precedence,
  including arbitrarily large numeric identifiers and build metadata, rather than trusting release
  publication order. Both SemVer and Android's 64-bit version code must increase, and new installs
  no longer opt into prerelease updates by default.
- Update downloads now require GitHub's published SHA-256 and asset size, reserve space for both
  the download and Package Installer staging copy, stop if bytes exceed the published size, and
  reject short or length-mismatched responses before installation.
- Release verification now fails if the package, version, signing certificate, model hash,
  compression method, native ABI set, or pinned Android build-tools version differs from the
  reviewed contract.

- Password, email, URL, and explicit no-suggestions editors now bypass the entire language path,
  including gesture decoding; sensitive fields can no longer receive a decoded swipe candidate.
- Date and time editor variations now receive dedicated pads with date separators, time punctuation,
  and AM/PM keys instead of an unusable digits-only pad.
- Moving the cursor clears stale prediction candidates and can reopen an earlier word even in
  editors that omit composing bounds. Model readiness can no longer start autocorrect halfway
  through a word, and tapping a candidate keeps the capitalization that was typed.
- Personal next-word predictions now honor offensive-word filtering, and learned words and phrases
  retain evidence-backed useful casing without letting one accidental capital replace an
  established spelling.
- Learned-data saves are serialized, failure-aware, and staged outside Android backup. Clearing
  uses a durable pending marker so interrupted deletion cannot make stale personal data visible
  again, and the tagged release workflow runs the correction unit suites before packaging.
- Updates: the downloader checks the HTTP status, the byte count, and the file's magic number, and
  only moves a download into place once it is whole — a truncated APK used to be kept under its
  final name and fail verification on every subsequent attempt. If the signing certificates cannot
  be read out of the archive, the update is no longer refused as unreadable; Android enforces the
  signature on install either way.
- Updates: `getPackageArchiveInfo` and `getPackageInfo` no longer call API 33-only overloads on a
  minSdk 26 app, which would have thrown `NoSuchMethodError` on Android 8 through 12.

## [0.2.0] - 2026-08-08

### Added

- Emoji search from the picker, with recent results, ranked CLDR keyword matches, and an explicit
  empty state.
- Cursor movement by swiping across Space and preceding-word deletion by swiping left from
  Backspace.
- Basic TalkBack descriptions and announcements for keyboard, suggestion, emoji, and voice-panel
  actions, with larger emoji-panel touch targets.
- Both documented Whisper models are packaged in the published APK for offline voice typing.

### Fixed

- Switching to `?123` now recomputes active key geometry immediately, keeping drawn symbols and
  hit testing in sync.
- Shift, caps lock, and editor-specific Enter actions now have distinct visual states.
- The tagged release workflow now packages the same Whisper models used by local builds.

## [0.1.0-alpha.6] - 2026-08-08

### Fixed

- The tagged release workflow now downloads and packages both documented Whisper models, so
  offline voice typing is present in published APKs as it is in local builds.

## [0.1.0-alpha.5] - 2026-08-08

### Added

- Emoji search from the picker, with recent results, ranked CLDR keyword matches, an empty state,
  and a keyboard-backed query flow.
- Cursor movement by swiping across Space and preceding-word deletion by swiping left from
  Backspace.
- Basic TalkBack descriptions and announcements for keyboard, suggestion, emoji, and voice-panel
  actions; larger emoji-panel touch targets.

### Fixed

- Switching to `?123` now recomputes the active key geometry immediately, so the drawn symbols and
  the hit-test layer cannot remain on QWERTY for one frame or until a later layout pass.
- Shift, caps lock, and editor-specific Enter actions now have distinct visual states.

## [0.1.0-alpha.4] - 2026-08-08

### Changed

- Enlarged and optically balanced the keyboard action icons, replaced the microphone placeholder with a clearer microphone, and made long-press Backspace begin repeating sooner.

## [0.1.0-alpha.3] - 2026-08-08

### Changed

- Enabled update checks now run automatically when Slide opens; users still approve every download and Android installation.

## [0.1.0-alpha.2] - 2026-08-08

### Added

- Opt-in GitHub update checks, including the alpha channel. Downloaded APKs are checked for a newer version and Slide's signing certificate before Android's installer is opened.

## [0.1.0-alpha.1] - 2026-08-08

### Added

- Initial public alpha of Slide, an English-only offline Android keyboard.
- QWERTY typing, themes, gesture typing, local voice-typing infrastructure, typed-word suggestions, autocorrect, and an emoji picker.

### Known limitations

- Voice typing and the default Whisper model selection have not yet been verified on physical hardware.
- Next-word prediction, a personal dictionary, emoji search, clipboard/text-editing panels, and the accessibility pass are not yet complete.
