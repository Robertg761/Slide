#!/usr/bin/env python3
"""
Builds Slide's bigram language model from a plain-text sentence corpus.

Source:  Tatoeba's English sentence export (CC BY 2.0 FR, https://tatoeba.org).
Output:  engine/src/main/assets/bigrams_en.bin
         engine/src/test/resources/heldout_en.txt   (evaluation only, never shipped)

Why a sentence corpus and not the AOSP wordlist: the AOSP combined wordlist Slide's lexicon comes
from carries no bigram entries at all, despite the format supporting them. Tatoeba is everyday
conversational English, which is a far better match for what people type into a phone than books
or an encyclopaedia would be, and its licence is clean enough to redistribute a derived model.

A tenth of the corpus is held out by sentence id and never contributes to the shipped model, so
the correction rates measured in `ContextualCorrectionTest` are measured on sentences the model
has not seen. Losing a tenth of the data costs almost nothing; being able to believe the numbers
is worth a great deal.

Bigrams are keyed by lexicon index, so this must be rebuilt whenever the lexicon is:

    "SBIG"          magic
    u8              format version
    u32             word count of the lexicon these indices refer to
    u32             context count (distinct preceding words that have successors)
    u32             pair count
    u32             byte length of the successor block
    [contexts]      context count x u32     ascending lexicon indices
    [offsets]       context count + 1 x u32 into the successor arrays
    [block]         varint deltas of successor indices, ascending within each context
    [scores]        pair count x u8         quantised log P(next | previous)

Usage:
    python3 tools/build_bigrams.py /tmp/tatoeba_eng.tsv \\
        engine/src/main/assets/lexicon_en.bin \\
        engine/src/main/assets/bigrams_en.bin \\
        engine/src/test/resources/heldout_en.txt
"""

from __future__ import annotations

import math
import re
import struct
import sys
from collections import defaultdict
from pathlib import Path

MAGIC = b"SBIG"
VERSION = 1

LEXICON_MAGIC = b"SLEX"

# Matches the lexicon's own notion of a word: letters and the apostrophe, nothing else.
TOKEN_RE = re.compile(r"[a-z']+")

# A pair seen once is as likely to be noise as signal, and singletons are over half the distinct
# pairs in any corpus this size. Dropping them costs almost no coverage and halves the asset.
MIN_PAIR_COUNT = 2

# A context with too little evidence cannot say anything useful about which of two candidates was
# meant, and its successors would be scored against a total that is mostly accident.
MIN_CONTEXT_TOTAL = 5

# Where the quantised score bottoms out. P(next | previous) below this is indistinguishable from
# "no information", so it is not worth spending a byte on.
PROBABILITY_FLOOR = 1e-4

# Sentences whose id ends in this digit are never trained on. See the module docstring.
HELDOUT_DIGIT = 7

# Tatoeba's stock characters and settings, excluded from every pair they appear in.
#
# These are not facts about English. Tatoeba uses Tom and Mary the way a maths textbook uses Alice
# and Bob, and Boston as its stock city, and it shows in the counts: "tom" is the third commonest
# token in the entire corpus at 2.9% — ahead of "I", "a" and "you" — "mary" is fifteenth at 1.0%,
# and "boston" outnumbers "london" twenty-one to one.
#
# Left in, that distortion reaches the user. The strip offered "thank tom", and a dropped letter in
# "from" was corrected to "tom", because a model built on this corpus believes "tom" follows almost
# anything. Note that removing them slightly *lowers* the measured correction rate, since the
# held-out sentences are from the same corpus and carry the same skew. That is the measurement
# losing its artefact, not the model losing accuracy.
#
# Only the pairs are dropped, not the sentences: everything else in a sentence about Tom is
# ordinary English and worth counting. The cost is that the genuine words "tom" (a male cat),
# "mary" and "boston" stop being predicted, which is a small price for not threading a textbook's
# stage names through someone's messages.
PLACEHOLDER_NAMES = frozenset({"tom", "mary", "boston"})


def read_lexicon(path: Path) -> list[str]:
    """Decodes the front-coded word list, so bigrams can be keyed by the same indices."""
    data = path.read_bytes()
    if data[:4] != LEXICON_MAGIC:
        raise SystemExit(f"{path} is not a Slide lexicon")

    version = data[4]
    if version != 1:
        raise SystemExit(f"lexicon version {version}, expected 1")

    count, block_length, _chars = struct.unpack(">III", data[5:17])
    block = data[17 : 17 + block_length]

    words: list[str] = []
    previous = ""
    read = 0
    for _ in range(count):
        shared = block[read]
        suffix_length = block[read + 1]
        read += 2
        word = previous[:shared] + block[read : read + suffix_length].decode("ascii")
        read += suffix_length
        words.append(word)
        previous = word
    return words


def sentences(path: Path) -> tuple[list[str], list[str]]:
    """Splits the corpus into training and held-out sentences by id."""
    train: list[str] = []
    heldout: list[str] = []
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            identifier, text = parts[0], parts[2]
            if not identifier.isdigit():
                continue
            (heldout if identifier[-1] == str(HELDOUT_DIGIT) else train).append(text)
    return train, heldout


def count_pairs(corpus: list[str], index_of: dict[str, int]) -> dict[int, dict[int, int]]:
    """
    Counts adjacent word pairs, keyed by lexicon index.

    Pairs are only counted within a sentence: the last word of one and the first of the next never
    followed each other in anything anyone wrote.
    """
    pairs: dict[int, dict[int, int]] = defaultdict(lambda: defaultdict(int))
    kept = 0
    dropped = 0
    for text in corpus:
        previous = -1
        for token in TOKEN_RE.findall(text.lower()):
            word = token.strip("'")
            if word in PLACEHOLDER_NAMES:
                # Breaks the chain rather than skipping the token, so the words either side of a
                # placeholder are not counted as adjacent to each other either. They were not.
                dropped += 1
                previous = -1
                continue
            current = index_of.get(word, -1)
            if previous >= 0 and current >= 0:
                pairs[previous][current] += 1
                kept += 1
            previous = current
    print(f"  counted       {kept:,} in-lexicon adjacencies")
    print(f"  placeholders  {dropped:,} occurrences excluded ({', '.join(sorted(PLACEHOLDER_NAMES))})")
    return pairs


def quantise(count: int, total: int) -> int:
    probability = count / total
    if probability <= PROBABILITY_FLOOR:
        return 0
    normalised = 1.0 + math.log(probability) / -math.log(PROBABILITY_FLOOR)
    return max(1, min(255, round(255 * normalised)))


def varint(value: int, out: bytearray) -> None:
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def encode(pairs: dict[int, dict[int, int]], word_count: int) -> bytes:
    contexts: list[int] = []
    offsets: list[int] = [0]
    block = bytearray()
    scores = bytearray()

    for context in sorted(pairs):
        successors = pairs[context]
        total = sum(successors.values())
        if total < MIN_CONTEXT_TOTAL:
            continue

        kept = sorted(
            (nxt, count) for nxt, count in successors.items() if count >= MIN_PAIR_COUNT
        )
        kept = [(nxt, quantise(count, total)) for nxt, count in kept]
        kept = [(nxt, score) for nxt, score in kept if score > 0]
        if not kept:
            continue

        contexts.append(context)
        previous = 0
        for nxt, score in kept:
            varint(nxt - previous, block)
            previous = nxt
            scores.append(score)
        offsets.append(len(scores))

    print(f"  contexts      {len(contexts):,}")
    print(f"  pairs         {len(scores):,}")

    out = bytearray()
    out.extend(MAGIC)
    out.append(VERSION)
    out.extend(struct.pack(">IIII", word_count, len(contexts), len(scores), len(block)))
    for context in contexts:
        out.extend(struct.pack(">I", context))
    for offset in offsets:
        out.extend(struct.pack(">I", offset))
    out.extend(block)
    out.extend(scores)
    return bytes(out)


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__)
        return 2

    corpus_path, lexicon_path, target, heldout_path = (Path(p) for p in sys.argv[1:5])

    print(f"reading {lexicon_path}")
    words = read_lexicon(lexicon_path)
    index_of = {word: index for index, word in enumerate(words)}
    print(f"  lexicon       {len(words):,} words")

    print(f"reading {corpus_path}")
    train, heldout = sentences(corpus_path)
    print(f"  training      {len(train):,} sentences")
    print(f"  held out      {len(heldout):,} sentences")
    if not train:
        print("no sentences parsed - is this the Tatoeba TSV export?", file=sys.stderr)
        return 1

    pairs = count_pairs(train, index_of)
    blob = encode(pairs, len(words))

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(blob)
    print(f"wrote {target}  {len(blob):,} bytes")

    # Only as many held-out sentences as the evaluation needs; the rest is dead weight in the repo.
    heldout_path.parent.mkdir(parents=True, exist_ok=True)
    sample = [s for s in heldout if 20 <= len(s) <= 120][:20000]
    heldout_path.write_text("\n".join(sample) + "\n", encoding="utf-8")
    print(f"wrote {heldout_path}  {len(sample):,} sentences")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
