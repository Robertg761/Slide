# Generated data provenance

Slide ships generated emoji and English-language data. The generated files are committed so an
ordinary app build needs no corpus download. `tools/language_sources.json` is the machine-readable
lock for both their inputs and outputs; SHA-256 plus byte length, not a branch name or HTTP URL,
defines each input.

## Locked inputs

| Input | Locked revision or snapshot | Compressed/source SHA-256 |
|---|---|---|
| Unicode `emoji-test.txt` | Unicode 17.0.0, dated 2025-08-04 | `1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda` |
| CLDR English annotations | `unicode-org/cldr` commit `f67d16c9f472012cf324b36ce37cf757d3185cc8` | `091807d3ec993e2bde057c39f75ce3a051764c2a12a6a96204c475c8f3fea817` |
| CLDR English derived annotations | `unicode-org/cldr` commit `f67d16c9f472012cf324b36ce37cf757d3185cc8` | `c3d08ed66d4f840ba8b1202a5c7f6c4336e3c65363c08144811dc3f587e92a80` |
| AOSP LatinIME English wordlist | LineageOS commit `c692b1224009953fb4677c99ad498e13bad6c85f` | `07682388185c285d307e341d1733331af8699f735b4137e9f22571017fab69d2` |
| Tatoeba English sentences | Export snapshot last modified 2026-08-08 06:29:57 UTC | `34fc76cc177ca65b56cb3b966a2b8f33efd8a1b0551eb855f31b5ab940d05e60` |

The Unicode URL embeds the release, and the GitHub raw URLs embed full commits. Tatoeba does not
publish a permanent dated per-language URL, so Slide preserves the exact authenticated compressed
snapshot at `third_party/language-data/tatoeba-eng-sentences-2026-08-08.tsv.bz2`. The moving URL is
only an origin record and refresh hint; ordinary reproduction reads the tracked archive, so it
continues to work after upstream rotates. A newer export is never silently substituted.

## Reproduce the committed assets

Fetch and authenticate the current locks:

```bash
python3 tools/fetch_language_sources.py --output-dir /tmp/slide-language-sources
```

The Tatoeba input is already available offline. The same command can authenticate a complete local
archive without network access:

```bash
python3 tools/fetch_language_sources.py \
    --source-dir /path/to/archived-slide-sources \
    --output-dir /tmp/slide-language-sources
```

Then rebuild in a temporary directory and compare every byte with the committed outputs:

```bash
python3 tools/rebuild_language_assets.py \
    --sources-dir /tmp/slide-language-sources --check
```

This rebuilds `emoji.bin`, `lexicon_en.bin`, `bigrams_en.bin`, `trigrams_en.bin`, and
`heldout_en.txt`. It authenticates all inputs before parsing, checks every generated output against
the output digest in the lock, and leaves the worktree untouched in check mode. `--write` performs
the same checks before atomically replacing the committed files.

## Refresh a source

Treat a data refresh like a dependency update:

1. Select an immutable release, full commit, or preserved snapshot.
2. Record its retrieval hint, human-readable revision, exact byte length, and SHA-256 in
   `tools/language_sources.json`.
3. Build into temporary outputs with the component builders, review parser statistics and the
   data/license change, then record the new generated-output SHA-256 values.
4. Run `tools/rebuild_language_assets.py --write`, the Python provenance tests, and the complete
   `:core` and `:engine` unit-test suites.
5. Keep coupled data together: any lexicon change requires rebuilding both context models and the
   held-out sample because model entries are lexicon indices.

`tools/build_emoji.py` applies the same locks even when local paths are passed directly. It also
rejects unknown Unicode groups and refuses to continue without valid CLDR keywords, preventing an
apparently successful but incomplete emoji-search asset.
