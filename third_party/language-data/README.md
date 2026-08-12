# Tatoeba source snapshot

`tatoeba-eng-sentences-2026-08-08.tsv.bz2` is the exact English per-language export used to build
Slide's committed bigram/trigram models and held-out evaluation sample. It is tracked because
Tatoeba's official download URL rotates in place and does not offer dated archives.

Its byte length, SHA-256, origin timestamp, generated outputs, and rebuild procedure are locked in
`tools/language_sources.json` and documented in `docs/data-provenance.md`. The sentences remain
copyright their individual Tatoeba contributors and are redistributed under CC BY 2.0 FR; Slide's
packaged third-party notices contain the attribution and licence link.
