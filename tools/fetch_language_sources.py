#!/usr/bin/env python3
"""Fetch and authenticate the source archives for Slide's generated language assets.

The URLs in language_sources.json are retrieval hints. SHA-256 and byte length are the
authoritative source identities, including for Tatoeba's moving export URL. A changed or
truncated response is rejected before anything is placed in the output directory.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

LOCK_PATH = Path(__file__).with_name("language_sources.json")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GROUPS = ("emoji", "lexicon", "context")
ROOT = Path(__file__).resolve().parent.parent
DOWNLOAD_CHUNK_SIZE = 64 * 1024


@dataclass(frozen=True)
class SourceSpec:
    key: str
    filename: str
    retrieval_url: str
    revision: str
    sha256: str
    size: int
    mutable_origin: bool
    group: str
    bundled_path: str | None = None


@dataclass(frozen=True)
class SourceLock:
    sources: dict[str, SourceSpec]
    generated_outputs: dict[str, str]


def load_lock(path: Path = LOCK_PATH) -> SourceLock:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schema_version") != 1:
        raise ValueError(f"unsupported source-lock schema in {path}")

    raw_sources = document.get("sources")
    raw_outputs = document.get("generated_outputs")
    if not isinstance(raw_sources, dict) or not isinstance(raw_outputs, dict):
        raise ValueError(f"malformed source lock in {path}")

    sources: dict[str, SourceSpec] = {}
    for key, raw in raw_sources.items():
        if not isinstance(key, str) or not isinstance(raw, dict):
            raise ValueError(f"malformed source entry in {path}")
        try:
            spec = SourceSpec(key=key, **raw)
        except TypeError as error:
            raise ValueError(f"malformed source entry {key!r}: {error}") from error
        validate_spec(spec)
        if spec.filename in {source.filename for source in sources.values()}:
            raise ValueError(f"duplicate source filename {spec.filename!r}")
        sources[key] = spec

    outputs: dict[str, str] = {}
    for target, digest in raw_outputs.items():
        if (
            not isinstance(target, str)
            or not isinstance(digest, str)
            or not SHA256_RE.fullmatch(digest)
        ):
            raise ValueError(f"invalid generated-output lock for {target!r}")
        outputs[target] = digest
    return SourceLock(sources=sources, generated_outputs=outputs)


def validate_spec(spec: SourceSpec) -> None:
    if not spec.filename or Path(spec.filename).name != spec.filename:
        raise ValueError(f"unsafe filename for source {spec.key!r}")
    if not spec.retrieval_url.startswith("https://"):
        raise ValueError(f"source {spec.key!r} must use HTTPS")
    if not spec.revision:
        raise ValueError(f"source {spec.key!r} has no revision description")
    if not SHA256_RE.fullmatch(spec.sha256):
        raise ValueError(f"source {spec.key!r} has an invalid SHA-256")
    if spec.size <= 0:
        raise ValueError(f"source {spec.key!r} has an invalid size")
    if spec.group not in GROUPS:
        raise ValueError(f"source {spec.key!r} has unknown group {spec.group!r}")
    if spec.bundled_path is not None:
        bundled = Path(spec.bundled_path)
        if (
            bundled.is_absolute()
            or ".." in bundled.parts
            or bundled.name != spec.filename
        ):
            raise ValueError(f"source {spec.key!r} has an unsafe bundled path")


def verify_source(data: bytes, spec: SourceSpec) -> bytes:
    actual_hash = hashlib.sha256(data).hexdigest()
    if len(data) != spec.size or actual_hash != spec.sha256:
        raise ValueError(
            f"{spec.key} does not match the lock: expected {spec.size} bytes and SHA-256 "
            f"{spec.sha256}, got {len(data)} bytes and {actual_hash}"
        )
    return data


def read_verified_stream(
    stream: BinaryIO,
    *,
    expected_size: int,
    expected_sha256: str,
    label: str,
    content_length: str | None = None,
    lock_description: str = "the locked source",
) -> bytes:
    """Read and authenticate a response without buffering past its locked size."""
    if content_length is not None:
        stripped_length = content_length.strip()
        if not stripped_length.isascii() or not stripped_length.isdecimal():
            raise ValueError(f"{label} returned an invalid Content-Length")
        declared_size = int(stripped_length)
        if declared_size != expected_size:
            raise ValueError(
                f"{label} Content-Length does not match {lock_description}: expected "
                f"{expected_size} bytes, got {declared_size}"
            )

    payload = bytearray()
    digest = hashlib.sha256()
    while True:
        # The extra byte distinguishes an exact response from an oversized response. This
        # also caps reads from responses without Content-Length, including chunked HTTP.
        remaining_with_overflow_byte = expected_size + 1 - len(payload)
        chunk = stream.read(min(DOWNLOAD_CHUNK_SIZE, remaining_with_overflow_byte))
        if not chunk:
            break
        if len(payload) + len(chunk) > expected_size:
            raise ValueError(
                f"{label} exceeds {lock_description} size of {expected_size} bytes"
            )
        payload.extend(chunk)
        digest.update(chunk)

    actual_hash = digest.hexdigest()
    if len(payload) != expected_size or actual_hash != expected_sha256:
        raise ValueError(
            f"{label} does not match {lock_description}: expected {expected_size} bytes "
            f"and SHA-256 {expected_sha256}, got {len(payload)} bytes and {actual_hash}"
        )
    return bytes(payload)


def download(spec: SourceSpec) -> bytes:
    print(f"fetching {spec.key}: {spec.retrieval_url}", file=sys.stderr)
    request = urllib.request.Request(
        spec.retrieval_url, headers={"User-Agent": "Slide-source-fetch/1"}
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return read_verified_stream(
            response,
            expected_size=spec.size,
            expected_sha256=spec.sha256,
            label=spec.key,
            content_length=response.headers.get("Content-Length"),
            lock_description="the lock",
        )


def materialize_source(
    spec: SourceSpec,
    output_dir: Path,
    local_source: Path | None = None,
) -> Path:
    if local_source is not None:
        data = verify_source(local_source.read_bytes(), spec)
    elif spec.bundled_path is not None:
        bundled = ROOT / spec.bundled_path
        if not bundled.is_file():
            raise ValueError(f"bundled source is missing: {spec.bundled_path}")
        data = verify_source(bundled.read_bytes(), spec)
    else:
        data = download(spec)
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / spec.filename

    if local_source is not None and local_source.resolve() == target.resolve():
        print(f"verified {spec.key}: {target} ({spec.sha256})")
        return target

    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=output_dir, prefix=f".{spec.filename}.", delete=False
        ) as handle:
            temporary = Path(handle.name)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)

    print(f"verified {spec.key}: {target} ({spec.sha256})")
    return target


def selected_sources(lock: SourceLock, groups: set[str]) -> list[SourceSpec]:
    return [spec for spec in lock.sources.values() if spec.group in groups]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--group",
        action="append",
        choices=GROUPS,
        help="fetch only this group; repeat for more than one (default: all)",
    )
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--source-dir",
        type=Path,
        help="verify locked filenames from this directory instead of using the network",
    )
    args = parser.parse_args()

    try:
        lock = load_lock()
        groups = set(args.group or GROUPS)
        specs = selected_sources(lock, groups)
        for spec in specs:
            local = (
                args.source_dir / spec.filename if args.source_dir is not None else None
            )
            materialize_source(spec, args.output_dir, local)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
