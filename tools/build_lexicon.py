#!/usr/bin/env python3
"""
Converts the AOSP LatinIME combined wordlist into Slide's packed lexicon asset.

Source:  dictionaries/en_wordlist.combined.gz from AOSP LatinIME (Apache-2.0).
Output:  engine/src/main/assets/lexicon_en.bin

The asset is front-coded (each entry stores only the suffix that differs from its
predecessor), which roughly halves the size and costs nothing to decode since we
read the whole file sequentially at startup anyway.

    "SLEX"          magic
    u8              format version
    u32             word count
    u32             byte length of the front-coded block
    u32             total decoded character count (front coding expands, so this is larger)
    [words]         per word: u8 shared prefix length, u8 suffix length, suffix (UTF-8)
    [freqs]         word count x u8   (0-255, AOSP's own scale)
    [flags]         word count x u8   (see FLAG_* below)

Usage:
    python3 tools/build_lexicon.py /tmp/aosp_en.txt engine/src/main/assets/lexicon_en.bin
"""

from __future__ import annotations

import re
import struct
import sys
from pathlib import Path

MAGIC = b"SLEX"
VERSION = 1

FLAG_OFFENSIVE = 1 << 0
FLAG_ABBREVIATION = 1 << 1
FLAG_CAPITALIZED = 1 << 2

# Gesture typing can only ever produce letter keys, so anything with a digit or symbol
# in it is dead weight in the decoder's search space. Apostrophes stay: "don't" and
# "it's" are among the most-swiped words in English, and the apostrophe is inferred
# rather than gestured.
#
# Capitals are accepted and then folded: the gesture for "September" is identical to the
# one for "september", so the lexicon is keyed lowercase and FLAG_CAPITALIZED restores the
# capital at output time. Dropping these outright would lose months, countries and given
# names -- about a third of the wordlist.
WORD_RE = re.compile(r"^[A-Za-z][A-Za-z']*$")

# AOSP marks a handful of entries as deliberate non-words (typo targets and the like).
# Those must never surface as a gesture candidate.
DROPPED_FLAGS = {"nonword"}

LINE_RE = re.compile(r"^ word=(?P<word>[^,]+),f=(?P<freq>-?\d+),flags=(?P<flags>[^,]*)")


def parse(source: Path) -> list[tuple[str, int, int]]:
    entries: dict[str, tuple[int, int]] = {}
    # Tracks whether a key was ever seen in lowercase. "March" and "march" are both real
    # words; when both exist the lowercase spelling wins so we never capitalise mid-sentence.
    seen_lowercase: set[str] = set()
    skipped_shape = 0
    skipped_nonword = 0

    with source.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = LINE_RE.match(line)
            if match is None:
                continue

            word = match.group("word")
            raw_flags = {f for f in match.group("flags").split() if f}

            if raw_flags & DROPPED_FLAGS:
                skipped_nonword += 1
                continue
            if not WORD_RE.match(word):
                skipped_shape += 1
                continue

            freq = max(0, min(255, int(match.group("freq"))))

            key = word.lower()
            flags = 0
            if "offensive" in raw_flags:
                flags |= FLAG_OFFENSIVE
            if "abbreviation" in raw_flags:
                flags |= FLAG_ABBREVIATION
            if word == key:
                seen_lowercase.add(key)
            else:
                flags |= FLAG_CAPITALIZED

            # The source lists some words twice (different case, or different frequencies);
            # keep the higher frequency and union the flags.
            previous = entries.get(key)
            if previous is None:
                entries[key] = (freq, flags)
            else:
                entries[key] = (max(freq, previous[0]), flags | previous[1])

    # A key seen in lowercase anywhere is a lowercase word, whatever else claimed it.
    for key in seen_lowercase:
        freq, flags = entries[key]
        entries[key] = (freq, flags & ~FLAG_CAPITALIZED)

    capitalised = sum(1 for freq, flags in entries.values() if flags & FLAG_CAPITALIZED)
    print(f"  kept          {len(entries)} ({capitalised} rendered capitalised)")
    print(f"  dropped shape {skipped_shape} (digits, symbols, accents, hyphens)")
    print(f"  dropped nonword {skipped_nonword}")

    return sorted((word, freq, flags) for word, (freq, flags) in entries.items())


def encode(entries: list[tuple[str, int, int]]) -> bytes:
    front_coded = bytearray()
    previous = ""

    for word, _, _ in entries:
        # Cap the shared prefix at 255 so it fits a byte; no English word gets close.
        limit = min(len(previous), len(word), 255)
        shared = 0
        while shared < limit and previous[shared] == word[shared]:
            shared += 1

        suffix = word[shared:].encode("utf-8")
        if len(suffix) > 255:
            raise ValueError(f"suffix too long to encode: {word!r}")

        front_coded.append(shared)
        front_coded.append(len(suffix))
        front_coded.extend(suffix)
        previous = word

    total_chars = sum(len(word) for word, _, _ in entries)

    out = bytearray()
    out.extend(MAGIC)
    out.append(VERSION)
    out.extend(struct.pack(">I", len(entries)))
    out.extend(struct.pack(">I", len(front_coded)))
    out.extend(struct.pack(">I", total_chars))
    out.extend(front_coded)
    out.extend(bytes(freq for _, freq, _ in entries))
    out.extend(bytes(flags for _, _, flags in entries))
    return bytes(out)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    source = Path(sys.argv[1])
    target = Path(sys.argv[2])

    print(f"reading {source}")
    entries = parse(source)
    if not entries:
        print("no entries parsed - is this an AOSP combined wordlist?", file=sys.stderr)
        return 1

    blob = encode(entries)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(blob)

    raw = sum(len(word) + 2 for word, _, _ in entries)
    print(f"wrote {target}  {len(blob):,} bytes (flat would be {raw:,})")
    print(f"  most frequent: {', '.join(w for w, _, _ in sorted(entries, key=lambda e: -e[1])[:12])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
