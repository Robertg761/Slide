#!/usr/bin/env python3
"""
Converts the Unicode emoji test data into Slide's packed emoji asset.

Sources:
    https://unicode.org/Public/emoji/latest/emoji-test.txt        (Unicode, UTS #51)
    https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/en.xml
        and .../annotationsDerived/en.xml                          (CLDR, search keywords)

Output: core/src/main/assets/emoji.bin

emoji-test.txt is already in the order a keyboard wants: CLDR's recommended presentation
order, grouped and subgrouped. We keep that order verbatim rather than inventing one.

Only `fully-qualified` entries are kept. The `minimally-qualified` and `unqualified` lines
are the same emoji missing their VS16 variation selectors -- they exist so implementations
can recognise older text, not so keyboards can offer them twice.

Skin-tone variants are folded into the base entry rather than listed beside it, which is
what makes them a long-press on one key instead of six near-identical keys in the grid.
Combinations naming two tones at once ("people holding hands: light skin tone, dark skin
tone") are dropped: there are 25 of them per base emoji, and no picker can show that as a
flat row.

    "SEMJ"          magic
    u8              format version
    u8              category count
    [categories]    per category: u8 name length, name (UTF-8)
    u16             entry count
    [entries]       per entry:
                        u8      category index
                        u8      emoji byte length, emoji (UTF-8)
                        u8      variant count
                        [variants]  per variant: u8 byte length, emoji (UTF-8)
                        u16     search text byte length, search text (UTF-8)

Search text is the emoji's name followed by its CLDR keywords, lowercased and separated by
spaces, so a substring match over it is the whole of search.

Usage:
    python3 tools/build_emoji.py                       # downloads its own sources
    python3 tools/build_emoji.py --emoji-test PATH --annotations PATH --derived PATH
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path

MAGIC = b"SEMJ"
VERSION = 1

EMOJI_TEST_URL = "https://unicode.org/Public/emoji/latest/emoji-test.txt"
CLDR_BASE = "https://raw.githubusercontent.com/unicode-org/cldr/main/common"
ANNOTATIONS_URL = f"{CLDR_BASE}/annotations/en.xml"
DERIVED_URL = f"{CLDR_BASE}/annotationsDerived/en.xml"

DEFAULT_OUTPUT = Path("core/src/main/assets/emoji.bin")

# The "Component" group holds bare skin-tone swatches and hair colours. They are pieces of
# other emoji, not emoji anyone picks, so the group never reaches the keyboard.
SKIPPED_GROUPS = {"Component"}

# Shortened for a tab strip. The full CLDR names are far too long to sit under an icon.
CATEGORY_NAMES = {
    "Smileys & Emotion": "Smileys",
    "People & Body": "People",
    "Animals & Nature": "Nature",
    "Food & Drink": "Food",
    "Travel & Places": "Travel",
    "Activities": "Activities",
    "Objects": "Objects",
    "Symbols": "Symbols",
    "Flags": "Flags",
}

TONE_ORDER = [
    "light skin tone",
    "medium-light skin tone",
    "medium skin tone",
    "medium-dark skin tone",
    "dark skin tone",
]

GROUP_RE = re.compile(r"^#\s*group:\s*(.+?)\s*$")
ENTRY_RE = re.compile(
    r"^(?P<code>[0-9A-F ]+?)\s*;\s*(?P<status>[a-z-]+)\s*#\s*\S+\s+E\d+(?:\.\d+)?\s+(?P<name>.+?)\s*$"
)


class Entry:
    __slots__ = ("emoji", "name", "category", "variants")

    def __init__(self, emoji: str, name: str, category: str) -> None:
        self.emoji = emoji
        self.name = name
        self.category = category
        self.variants: dict[str, str] = {}


def fetch(url: str) -> str:
    print(f"  fetching {url}", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read().decode("utf-8")


def read_source(path: str | None, url: str) -> str:
    return Path(path).read_text(encoding="utf-8") if path else fetch(url)


def parse_emoji_test(text: str) -> list[Entry]:
    """Returns entries in file order, with skin-tone variants folded into their base."""
    entries: list[Entry] = []
    by_name: dict[str, Entry] = {}
    deferred: list[tuple[str, str, str]] = []  # (base name, tone, emoji)
    multi_toned = 0
    group = ""

    for line in text.splitlines():
        matched_group = GROUP_RE.match(line)
        if matched_group:
            group = matched_group.group(1)
            continue
        if line.startswith("#") or not line.strip():
            continue

        match = ENTRY_RE.match(line)
        if not match or match.group("status") != "fully-qualified":
            continue
        if group in SKIPPED_GROUPS:
            continue
        category = CATEGORY_NAMES.get(group)
        if category is None:
            print(f"warning: unknown group {group!r}, skipping", file=sys.stderr)
            continue

        emoji = "".join(chr(int(point, 16)) for point in match.group("code").split())
        name = match.group("name")

        base_name, tone, tone_count = split_tone(name)
        if tone_count > 1:
            # A two-person emoji naming a tone for each person. Dropped outright: as its own key
            # it would put 25 near-identical handshakes in the grid, and it cannot hang off the
            # base as a variant either, since the long-press row has room for one tone.
            multi_toned += 1
        elif tone is None:
            entry = Entry(emoji, name, category)
            entries.append(entry)
            by_name[name] = entry
        else:
            # The base may not have been seen yet for a few sequences, so resolve after the pass.
            deferred.append((base_name, tone, emoji))

    orphans = 0
    for base_name, tone, emoji in deferred:
        entry = by_name.get(base_name)
        if entry is None:
            orphans += 1
            continue
        entry.variants[tone] = emoji
    if orphans:
        print(f"note: {orphans} toned sequences had no untoned base and were dropped", file=sys.stderr)
    if multi_toned:
        print(f"note: {multi_toned} multi-person tone combinations were dropped", file=sys.stderr)

    return entries


def split_tone(name: str) -> tuple[str, str | None, int]:
    """
    Splits "waving hand: light skin tone" into its base name and tone.

    Returns the name unchanged with a null tone when there is none to split off ("flag: Japan"),
    and reports how many tones were named so the caller can tell a single-tone variant apart
    from a multi-person combination.
    """
    if ": " not in name:
        return name, None, 0
    base, _, qualifiers = name.partition(": ")
    parts = [part.strip() for part in qualifiers.split(",")]
    tones = [part for part in parts if part in TONE_ORDER]
    if len(tones) != 1:
        return name, None, len(tones)

    # Anything else after the colon is part of the identity of the emoji, not a tone applied
    # to it -- "man: red hair" is its own key, so it keeps its qualifiers in the base name.
    others = [part for part in parts if part not in TONE_ORDER]
    base_name = f"{base}: {', '.join(others)}" if others else base
    return base_name, tones[0], 1


def parse_annotations(*documents: str) -> dict[str, list[str]]:
    """Maps emoji to its CLDR search keywords."""
    keywords: dict[str, list[str]] = {}
    for text in documents:
        root = ElementTree.fromstring(text)
        for annotation in root.iter("annotation"):
            # Entries carrying type="tts" are the spoken name, which we already have.
            if annotation.get("type") == "tts":
                continue
            emoji = annotation.get("cp")
            if not emoji or not annotation.text:
                continue
            keywords.setdefault(emoji, []).extend(
                word.strip() for word in annotation.text.split("|") if word.strip()
            )
    return keywords


def search_text(entry: Entry, keywords: dict[str, list[str]]) -> str:
    terms = [entry.name.replace(":", " ").replace(",", " ")]
    terms.extend(keywords.get(entry.emoji, ()))
    seen: dict[str, None] = {}
    for word in " ".join(terms).lower().split():
        seen.setdefault(word, None)
    return " ".join(seen)


def pack(entries: list[Entry], categories: list[str], keywords: dict[str, list[str]]) -> bytes:
    out = bytearray(MAGIC)
    out.append(VERSION)

    out.append(len(categories))
    for name in categories:
        write_short_string(out, name, f"category {name!r}")

    out += struct.pack(">H", len(entries))
    index_of = {name: index for index, name in enumerate(categories)}
    for entry in entries:
        out.append(index_of[entry.category])
        write_short_string(out, entry.emoji, f"emoji {entry.name!r}")

        variants = [entry.variants[tone] for tone in TONE_ORDER if tone in entry.variants]
        # A partial set would leave the long-press row with gaps that mean nothing to a user,
        # so it is all five tones or none.
        if len(variants) != len(TONE_ORDER):
            variants = []
        out.append(len(variants))
        for variant in variants:
            write_short_string(out, variant, f"variant of {entry.name!r}")

        text = search_text(entry, keywords).encode("utf-8")
        out += struct.pack(">H", len(text))
        out += text

    return bytes(out)


def write_short_string(out: bytearray, value: str, what: str) -> None:
    encoded = value.encode("utf-8")
    if len(encoded) > 255:
        raise ValueError(f"{what} is {len(encoded)} bytes, over the 255 the format allows")
    out.append(len(encoded))
    out += encoded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emoji-test", help="local copy of emoji-test.txt")
    parser.add_argument("--annotations", help="local copy of CLDR annotations/en.xml")
    parser.add_argument("--derived", help="local copy of CLDR annotationsDerived/en.xml")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    entries = parse_emoji_test(read_source(args.emoji_test, EMOJI_TEST_URL))
    if not entries:
        print("error: no emoji parsed; the source format may have changed", file=sys.stderr)
        return 1

    try:
        keywords = parse_annotations(
            read_source(args.annotations, ANNOTATIONS_URL),
            read_source(args.derived, DERIVED_URL),
        )
    except Exception as error:  # noqa: BLE001 - keywords improve search, they do not gate it
        print(f"warning: no CLDR keywords ({error}); search will use names alone", file=sys.stderr)
        keywords = {}

    categories = list(CATEGORY_NAMES.values())
    used = {entry.category for entry in entries}
    categories = [name for name in categories if name in used]

    payload = pack(entries, categories, keywords)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(payload)

    toned = sum(1 for entry in entries if len(entry.variants) == len(TONE_ORDER))
    searchable = sum(1 for entry in entries if keywords.get(entry.emoji))
    print(
        f"{len(entries)} emoji in {len(categories)} categories "
        f"({toned} with skin tones, {searchable} with CLDR keywords) "
        f"-> {args.output} ({len(payload) / 1024:.0f} KB)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
