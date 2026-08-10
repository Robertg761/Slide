# Changelog

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
