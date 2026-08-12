#!/usr/bin/env python3
"""Focused tests for deterministic release SBOM generation."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import build_sbom


class BuildSbomTest(unittest.TestCase):
    @staticmethod
    def write_artifact_manifest(path: Path, rows: list[list[str]]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            "\t".join(build_sbom.RUNTIME_ARTIFACT_HEADER)
            + "\n"
            + "".join("\t".join(row) + "\n" for row in rows),
            encoding="utf-8",
        )

    def test_current_runtime_is_complete_and_deterministic(self) -> None:
        commit = "a" * 40
        first = build_sbom.build_bom(
            build_sbom.ROOT, "9.8.7", commit, build_sbom.COMPONENTS_FILE
        )
        second = build_sbom.build_bom(
            build_sbom.ROOT, "9.8.7", commit, build_sbom.COMPONENTS_FILE
        )
        self.assertEqual(
            json.dumps(first, sort_keys=True, separators=(",", ":")),
            json.dumps(second, sort_keys=True, separators=(",", ":")),
        )
        self.assertRegex(
            first["serialNumber"],
            r"^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-"
            r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        changed_source = build_sbom.build_bom(
            build_sbom.ROOT, "9.8.7", "b" * 40, build_sbom.COMPONENTS_FILE
        )
        self.assertNotEqual(first["serialNumber"], changed_source["serialNumber"])
        self.assertNotIn("dependencies", first)
        components = first["components"]
        names = {component["name"] for component in components}
        self.assertIn("whisper.cpp (Slide provenance patch)", names)
        self.assertIn("Whisper base.en q5_1", names)
        self.assertIn("FUTO Swipe encoder", names)
        self.assertIn("Slide emoji catalogue", names)
        self.assertTrue(
            any(str(item["purl"]).startswith("pkg:maven/") for item in components)
        )
        self.assertFalse(any(name.endswith("-bom") for name in names))

        coordinates = {
            (str(component["name"]), str(component["version"]))
            for component in components
        }
        self.assertIn(("ui-android", "1.11.4"), coordinates)
        self.assertNotIn(("ui", "1.11.4"), coordinates)
        self.assertIn(("lifecycle-runtime-android", "2.11.0"), coordinates)
        self.assertNotIn(("lifecycle-runtime", "2.11.0"), coordinates)
        self.assertIn(("kotlinx-coroutines-core-jvm", "1.10.2"), coordinates)
        self.assertNotIn(("kotlinx-coroutines-core", "1.10.2"), coordinates)
        self.assertNotIn(("lifecycle-runtime", "2.6.2"), coordinates)
        self.assertNotIn(("collection-jvm", "1.4.2"), coordinates)
        self.assertNotIn(("kotlinx-coroutines-core", "1.9.0"), coordinates)
        for component in components:
            if str(component["purl"]).startswith("pkg:maven/"):
                self.assertEqual(1, len(component["hashes"]))

        executorch = next(
            component
            for component in components
            if component["name"] == "ExecuTorch Android (Slide patch)"
        )
        self.assertEqual("1.2.0-slide.1", executorch["version"])
        self.assertEqual(
            "pkg:generic/com.slide/executorch-android-patched@1.2.0-slide.1",
            executorch["purl"],
        )

        whisper = next(
            component
            for component in components
            if component["name"] == "whisper.cpp (Slide provenance patch)"
        )
        self.assertEqual(
            "592feef04a1802b18cbeffd0fd0eb5d02570c2ec-slide.1", whisper["version"]
        )
        self.assertEqual(
            "pkg:generic/com.slide/whisper.cpp-patched@"
            "592feef04a1802b18cbeffd0fd0eb5d02570c2ec-slide.1",
            whisper["purl"],
        )
        self.assertEqual(
            "third_party/whisper.cpp",
            whisper["properties"][0]["value"],
        )
        self.assertEqual("slide:tree-hash-format", whisper["properties"][1]["name"])
        self.assertEqual("597", whisper["properties"][2]["value"])
        self.assertEqual(
            build_sbom.directory_tree_sha256(
                build_sbom.ROOT / "third_party/whisper.cpp"
            )[0],
            whisper["hashes"][0]["content"],
        )
        self.assertEqual(
            "https://github.com/ggerganov/whisper.cpp/tree/"
            "592feef04a1802b18cbeffd0fd0eb5d02570c2ec",
            whisper["externalReferences"][0]["url"],
        )

    def test_directory_component_hash_covers_every_path_and_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tree = Path(directory)
            (tree / "src").mkdir()
            (tree / "CMakeLists.txt").write_text("build", encoding="utf-8")
            source = tree / "src/library.cpp"
            source.write_text("first", encoding="utf-8")

            initial, count = build_sbom.directory_tree_sha256(tree)
            self.assertEqual(2, count)

            source.write_text("second", encoding="utf-8")
            changed_content, _ = build_sbom.directory_tree_sha256(tree)
            self.assertNotEqual(initial, changed_content)

            source.rename(tree / "src/renamed.cpp")
            changed_path, _ = build_sbom.directory_tree_sha256(tree)
            self.assertNotEqual(changed_content, changed_path)

    def test_only_exact_resolved_artifacts_are_emitted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            (root / "engine").mkdir()
            artifact = root / "runtime-android-2.0.aar"
            artifact.write_bytes(b"selected artifact")
            artifact_manifest = root / "resolved.tsv"
            self.write_artifact_manifest(
                artifact_manifest,
                [
                    [
                        "maven",
                        "example",
                        "runtime-android",
                        "2.0",
                        str(artifact),
                        build_sbom.file_sha256(artifact),
                    ]
                ],
            )
            # Lock metadata deliberately contains the generic alias, a BOM, and
            # a stale library resolution. None has a selected runtime artifact.
            (root / "app/gradle.lockfile").write_text(
                "example:example-bom:2.0=releaseRuntimeClasspath\n"
                "example:runtime:2.0=releaseRuntimeClasspath\n",
                encoding="utf-8",
            )
            (root / "engine/gradle.lockfile").write_text(
                "example:runtime:1.0=releaseRuntimeClasspath\n",
                encoding="utf-8",
            )

            components = build_sbom.maven_components(root, artifact_manifest)

            self.assertEqual(
                [("runtime-android", "2.0")],
                [(component["name"], component["version"]) for component in components],
            )
            self.assertEqual(
                build_sbom.file_sha256(artifact), components[0]["hashes"][0]["content"]
            )

    def test_duplicate_resolved_coordinate_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "runtime-1.jar"
            second = root / "runtime-2.jar"
            first.write_bytes(b"one")
            second.write_bytes(b"two")
            artifact_manifest = root / "resolved.tsv"
            self.write_artifact_manifest(
                artifact_manifest,
                [
                    [
                        "maven",
                        "example",
                        "runtime",
                        "1",
                        str(first),
                        build_sbom.file_sha256(first),
                    ],
                    [
                        "maven",
                        "example",
                        "runtime",
                        "2",
                        str(second),
                        build_sbom.file_sha256(second),
                    ],
                ],
            )

            with self.assertRaisesRegex(ValueError, "duplicate resolved coordinate"):
                build_sbom.maven_components(root, artifact_manifest)

    def test_resolved_artifact_hash_mismatch_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "runtime.jar"
            artifact.write_bytes(b"changed")
            artifact_manifest = root / "resolved.tsv"
            self.write_artifact_manifest(
                artifact_manifest,
                [["maven", "example", "runtime", "1", str(artifact), "0" * 64]],
            )

            with self.assertRaisesRegex(
                ValueError, "resolved runtime artifact hash changed"
            ):
                build_sbom.maven_components(root, artifact_manifest)

    def test_local_classpath_artifacts_must_match_reviewed_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "local.aar"
            artifact.write_bytes(b"reviewed local runtime")
            digest = build_sbom.file_sha256(artifact)
            artifact_manifest = root / "resolved.tsv"
            self.write_artifact_manifest(
                artifact_manifest,
                [["file", "-", "-", "-", "local.aar", digest]],
            )
            components_manifest = root / "components.tsv"
            components_manifest.write_text(
                "library\tlocal\t1\tpkg:generic/local@1\tMIT\tlocal.aar\t"
                f"{digest}\thttps://example.invalid/local\n",
                encoding="utf-8",
            )
            bom = build_sbom.build_bom(
                root, "1", "a" * 40, components_manifest, artifact_manifest
            )
            self.assertEqual(
                ["local"], [component["name"] for component in bom["components"]]
            )

            self.write_artifact_manifest(artifact_manifest, [])
            with self.assertRaisesRegex(ValueError, "not-selected=.*local.aar"):
                build_sbom.build_bom(
                    root, "1", "a" * 40, components_manifest, artifact_manifest
                )

    def test_runtime_hash_mismatch_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "asset.bin"
            artifact.write_bytes(b"changed")
            manifest = root / "components.tsv"
            manifest.write_text(
                "file\tasset\t1\tpkg:generic/asset@1\tMIT\tasset.bin\t"
                + "0" * 64
                + "\thttps://example.invalid/asset\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "runtime artifact hash changed"):
                build_sbom.runtime_components(root, manifest)


if __name__ == "__main__":
    unittest.main()
