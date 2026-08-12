#!/usr/bin/env python3
"""Rebuild every committed language asset from the authenticated source lock."""

from __future__ import annotations

import argparse
import bz2
import gzip
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

from fetch_language_sources import load_lock, materialize_source

ROOT = Path(__file__).resolve().parent.parent

SOURCE_KEYS = {
    "emoji": "unicode_emoji_test",
    "annotations": "cldr_annotations_en",
    "derived": "cldr_annotations_derived_en",
    "lexicon": "aosp_english_wordlist",
    "context": "tatoeba_english_sentences",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def decompress(source: Path, target: Path, kind: str) -> None:
    opener = gzip.open if kind == "gzip" else bz2.open
    with opener(source, "rb") as compressed, target.open("wb") as plain:
        shutil.copyfileobj(compressed, plain, length=1024 * 1024)


def run(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def replace_file(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    target_mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else 0o644
    descriptor, temporary_name = tempfile.mkstemp(
        dir=target.parent, prefix=f".{target.name}."
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle, source.open("rb") as built:
            shutil.copyfileobj(built, handle, length=1024 * 1024)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, target_mode)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--check", action="store_true", help="verify committed assets (the default)"
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="replace committed assets after verification",
    )
    parser.add_argument(
        "--sources-dir",
        type=Path,
        help="use an archived set of locked source files instead of downloading retrieval hints",
    )
    args = parser.parse_args()

    try:
        lock = load_lock()
        with tempfile.TemporaryDirectory(
            prefix="slide-language-build-"
        ) as temporary_name:
            work = Path(temporary_name)
            source_dir = work / "sources"
            sources: dict[str, Path] = {}
            for role, key in SOURCE_KEYS.items():
                spec = lock.sources[key]
                local = (
                    args.sources_dir / spec.filename
                    if args.sources_dir is not None
                    else None
                )
                sources[role] = materialize_source(spec, source_dir, local)

            lexicon_text = work / "aosp-en-wordlist.combined"
            context_text = work / "tatoeba-eng-sentences.tsv"
            decompress(sources["lexicon"], lexicon_text, "gzip")
            decompress(sources["context"], context_text, "bzip2")

            built = work / "built"
            outputs = {target: built / target for target in lock.generated_outputs}
            run(
                [
                    sys.executable,
                    str(ROOT / "tools/build_emoji.py"),
                    "--emoji-test",
                    str(sources["emoji"]),
                    "--annotations",
                    str(sources["annotations"]),
                    "--derived",
                    str(sources["derived"]),
                    "--output",
                    str(outputs["core/src/main/assets/emoji.bin"]),
                ]
            )
            run(
                [
                    sys.executable,
                    str(ROOT / "tools/build_lexicon.py"),
                    str(lexicon_text),
                    str(outputs["engine/src/main/assets/lexicon_en.bin"]),
                ]
            )
            run(
                [
                    sys.executable,
                    str(ROOT / "tools/build_bigrams.py"),
                    str(context_text),
                    str(outputs["engine/src/main/assets/lexicon_en.bin"]),
                    str(outputs["engine/src/main/assets/bigrams_en.bin"]),
                    str(outputs["engine/src/test/resources/heldout_en.txt"]),
                    str(outputs["engine/src/main/assets/trigrams_en.bin"]),
                ]
            )

            for target_name, expected_hash in lock.generated_outputs.items():
                actual_hash = sha256(outputs[target_name])
                if actual_hash != expected_hash:
                    raise ValueError(
                        f"rebuilt {target_name} has SHA-256 {actual_hash}, expected {expected_hash}"
                    )

            if args.write:
                for target_name in lock.generated_outputs:
                    replace_file(outputs[target_name], ROOT / target_name)
                print("Rebuilt and replaced all locked language assets.")
            else:
                for target_name, expected_hash in lock.generated_outputs.items():
                    committed = ROOT / target_name
                    actual_hash = sha256(committed)
                    if (
                        actual_hash != expected_hash
                        or committed.read_bytes() != outputs[target_name].read_bytes()
                    ):
                        raise ValueError(
                            f"committed {target_name} differs from the reproducible build"
                        )
                print(
                    "All committed language assets reproduce exactly from the locked sources."
                )
    except (
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
        subprocess.CalledProcessError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
