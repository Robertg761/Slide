#!/usr/bin/env python3
"""Focused tests for generated-language-data provenance and fail-closed behavior."""

from __future__ import annotations

import hashlib
import io
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import build_emoji
from rebuild_language_assets import replace_file
from fetch_language_sources import (
    SourceSpec,
    download,
    load_lock,
    materialize_source,
    read_verified_stream,
    verify_source,
)

ROOT = Path(__file__).resolve().parent.parent


def fixture_spec(data: bytes) -> SourceSpec:
    return SourceSpec(
        key="fixture",
        filename="fixture.dat",
        retrieval_url="https://example.invalid/fixture.dat",
        revision="test fixture",
        sha256=hashlib.sha256(data).hexdigest(),
        size=len(data),
        mutable_origin=False,
        group="emoji",
    )


class ChunkedResponse(io.BytesIO):
    def __init__(
        self,
        data: bytes,
        *,
        max_chunk_size: int,
        content_length: str | None = None,
    ) -> None:
        super().__init__(data)
        self.max_chunk_size = max_chunk_size
        self.headers = (
            {} if content_length is None else {"Content-Length": content_length}
        )
        self.read_sizes: list[int] = []
        self.bytes_read = 0

    def read(self, size: int = -1, /) -> bytes:
        self.read_sizes.append(size)
        chunk = super().read(min(size, self.max_chunk_size))
        self.bytes_read += len(chunk)
        return chunk

    def __enter__(self) -> ChunkedResponse:
        return self

    def __exit__(self, *args: object) -> None:
        self.close()


class LanguageSourceLockTest(unittest.TestCase):
    def test_atomic_output_replacement_preserves_existing_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_name:
            directory = Path(temporary_name)
            source = directory / "built.bin"
            target = directory / "committed.bin"
            source.write_bytes(b"replacement")
            target.write_bytes(b"old")
            os.chmod(target, 0o640)

            replace_file(source, target)

            self.assertEqual(b"replacement", target.read_bytes())
            self.assertEqual(0o640, stat.S_IMODE(target.stat().st_mode))

    def test_atomic_output_replacement_uses_public_mode_for_new_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_name:
            directory = Path(temporary_name)
            source = directory / "built.bin"
            target = directory / "new.bin"
            source.write_bytes(b"replacement")

            replace_file(source, target)

            self.assertEqual(0o644, stat.S_IMODE(target.stat().st_mode))

    def test_locked_outputs_match_committed_files(self) -> None:
        lock = load_lock()
        for relative, expected in lock.generated_outputs.items():
            self.assertEqual(
                expected, hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
            )

    def test_immutable_sources_use_revisioned_urls(self) -> None:
        lock = load_lock()
        immutable = [
            source for source in lock.sources.values() if not source.mutable_origin
        ]
        self.assertGreaterEqual(len(immutable), 4)
        for source in immutable:
            self.assertNotIn("/latest/", source.retrieval_url)
            self.assertNotIn("/main/", source.retrieval_url)
            self.assertRegex(source.sha256, r"^[0-9a-f]{64}$")

    def test_tatoeba_moving_url_is_snapshot_locked(self) -> None:
        source = load_lock().sources["tatoeba_english_sentences"]
        self.assertTrue(source.mutable_origin)
        self.assertIn("2026-08-08", source.filename)
        self.assertIn("2026-08-08", source.revision)
        self.assertRegex(source.sha256, r"^[0-9a-f]{64}$")
        self.assertIsNotNone(source.bundled_path)
        bundled = ROOT / str(source.bundled_path)
        self.assertTrue(bundled.is_file())
        self.assertEqual(source.size, bundled.stat().st_size)
        self.assertEqual(
            source.sha256, hashlib.sha256(bundled.read_bytes()).hexdigest()
        )

    def test_local_source_mismatch_is_rejected_without_output(self) -> None:
        expected = b"locked bytes"
        spec = fixture_spec(expected)
        with tempfile.TemporaryDirectory() as temporary_name:
            directory = Path(temporary_name)
            local = directory / "wrong.dat"
            local.write_bytes(b"different bytes")
            with self.assertRaisesRegex(ValueError, "does not match the lock"):
                materialize_source(spec, directory / "out", local)
            self.assertFalse((directory / "out" / spec.filename).exists())

    def test_verified_local_source_is_materialized_exactly(self) -> None:
        expected = b"locked bytes"
        spec = fixture_spec(expected)
        with tempfile.TemporaryDirectory() as temporary_name:
            directory = Path(temporary_name)
            local = directory / "source.dat"
            local.write_bytes(expected)
            target = materialize_source(spec, directory / "out", local)
            self.assertEqual(expected, target.read_bytes())
            self.assertEqual(expected, verify_source(target.read_bytes(), spec))

    def test_unknown_emoji_group_fails_instead_of_being_dropped(self) -> None:
        source = "# group: Future Group\n1F600 ; fully-qualified # 😀 E1.0 face\n"
        with self.assertRaisesRegex(ValueError, "unknown emoji group"):
            build_emoji.parse_emoji_test(source)

    def test_build_emoji_local_source_uses_the_lock(self) -> None:
        spec = {"size": 4, "sha256": hashlib.sha256(b"good").hexdigest()}
        with tempfile.TemporaryDirectory() as temporary_name:
            source = Path(temporary_name) / "source.txt"
            source.write_bytes(b"evil")
            with self.assertRaisesRegex(ValueError, "does not match the locked source"):
                build_emoji.read_source(str(source), spec, "fixture")

    def test_bounded_reader_accepts_chunked_exact_response(self) -> None:
        expected = b"locked bytes split into chunks"
        response = ChunkedResponse(expected, max_chunk_size=3)

        actual = read_verified_stream(
            response,
            expected_size=len(expected),
            expected_sha256=hashlib.sha256(expected).hexdigest(),
            label="fixture",
        )

        self.assertEqual(expected, actual)
        self.assertGreater(len(response.read_sizes), 2)
        self.assertLessEqual(max(response.read_sizes), len(expected) + 1)

    def test_bounded_reader_stops_at_first_oversized_byte(self) -> None:
        expected = b"locked bytes"
        response = ChunkedResponse(expected + (b"x" * 1_000_000), max_chunk_size=2)

        with self.assertRaisesRegex(ValueError, "exceeds the locked source size"):
            read_verified_stream(
                response,
                expected_size=len(expected),
                expected_sha256=hashlib.sha256(expected).hexdigest(),
                label="fixture",
            )

        self.assertEqual(len(expected) + 1, response.bytes_read)
        self.assertLess(response.bytes_read, 1_000_000)

    def test_bounded_reader_rejects_content_length_before_reading(self) -> None:
        expected = b"locked bytes"
        response = ChunkedResponse(expected, max_chunk_size=2)

        with self.assertRaisesRegex(ValueError, "Content-Length does not match"):
            read_verified_stream(
                response,
                expected_size=len(expected),
                expected_sha256=hashlib.sha256(expected).hexdigest(),
                label="fixture",
                content_length=str(len(expected) + 1),
            )

        self.assertEqual([], response.read_sizes)

    def test_both_network_fetch_paths_use_the_bounded_reader(self) -> None:
        expected = b"good"
        source_spec = fixture_spec(expected)
        emoji_spec: dict[str, object] = {
            "retrieval_url": source_spec.retrieval_url,
            "size": source_spec.size,
            "sha256": source_spec.sha256,
        }

        source_response = ChunkedResponse(expected + b"overflow", max_chunk_size=2)
        with mock.patch(
            "fetch_language_sources.urllib.request.urlopen",
            return_value=source_response,
        ):
            with self.assertRaisesRegex(ValueError, "exceeds the lock size"):
                download(source_spec)
        self.assertEqual(len(expected) + 1, source_response.bytes_read)

        emoji_response = ChunkedResponse(expected + b"overflow", max_chunk_size=2)
        with mock.patch.object(
            build_emoji.urllib.request, "urlopen", return_value=emoji_response
        ):
            with self.assertRaisesRegex(ValueError, "exceeds the locked source size"):
                build_emoji.fetch(emoji_spec, "fixture")
        self.assertEqual(len(expected) + 1, emoji_response.bytes_read)


if __name__ == "__main__":
    unittest.main()
