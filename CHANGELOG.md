# Changelog

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
