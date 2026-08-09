# Changelog

## [Unreleased]

### Added

- A second symbols page behind `=\<`, carrying the slash, equals sign, brackets, angle brackets,
  pipe, caret, and currency symbols. The key previously asked for the page it was already on, so
  nothing happened when it was pressed.
- Tapping into a word that is already typed reopens it, so the suggestion strip offers replacements
  for it. Fixing a wrong swipe or a missed correction no longer means deleting back to it.

### Changed

- Autocorrect now fires on 85% of single-edit typos, up from 77%, with wrong corrections falling
  from 1.5% to 1.2%. The confidence margin was measured on a scale it did not fit: because the
  language score is a log of an already-logarithmic frequency, every word worth correcting to sits
  in a band about a quarter wide, and the old margin spent more than half of it. Typos of common
  words — "htis", "thjs", "drom", "witth" — had the right answer in the strip and were refused.
- A doubled letter is now priced as the mechanical slip it is rather than as an ordinary stray
  keystroke, so "largee" no longer becomes "larger" or "sidde" "sided". Nothing in that class is
  rewritten to a different word any more, where the corrector previously preferred changing the
  word to un-doubling the key.
- Backspace deletes on touch-down instead of on release, and its auto-repeat reaches full speed in
  about a third of the time it used to.
- The navigation-bar strip below the keys is painted in the keyboard's own background colour, so
  the keyboard meets the bottom of the screen without a black band and a visible seam.
- Swipes are reconstructed from every touch sample Android batches into a move event rather than
  only the newest, which gives the decoder the path the finger actually took on a fast swipe.
- A swipe too short for the decoder to read now types the key it started on instead of committing
  nothing.

### Fixed

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
