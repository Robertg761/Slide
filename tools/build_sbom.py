#!/usr/bin/env python3
"""Build a deterministic CycloneDX SBOM for Slide's shipped runtime."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import uuid
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
COMPONENTS_FILE = ROOT / "release/runtime-components.tsv"
RUNTIME_ARTIFACTS_RELATIVE = Path("app/build/reports/release-runtime-artifacts.tsv")
RUNTIME_ARTIFACTS_FILE = ROOT / RUNTIME_ARTIFACTS_RELATIVE
SHA256_RE = re.compile(r"[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
RUNTIME_ARTIFACT_HEADER = ["kind", "group", "name", "version", "path", "sha256"]


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def directory_tree_sha256(path: Path) -> tuple[str, int]:
    """Hash every regular file with an unambiguous, path-sensitive canonical encoding."""
    entries = list(path.rglob("*"))
    symlinks = [entry for entry in entries if entry.is_symlink()]
    if symlinks:
        raise ValueError(f"runtime component tree contains a symlink: {symlinks[0]}")
    files = sorted(
        (entry for entry in entries if entry.is_file()),
        key=lambda entry: entry.relative_to(path).as_posix().encode("utf-8"),
    )
    digest = hashlib.sha256(b"slide-tree-sha256-v1\0")
    for entry in files:
        relative = entry.relative_to(path).as_posix().encode("utf-8")
        size = entry.stat().st_size
        digest.update(relative)
        digest.update(b"\0")
        digest.update(str(size).encode("ascii"))
        digest.update(b"\0")
        with entry.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
    return digest.hexdigest(), len(files)


def resolved_runtime_artifacts(
    root: Path, manifest: Path | None = None
) -> tuple[list[dict[str, object]], dict[str, str]]:
    """Load and authenticate Gradle's exact release artifact selection."""
    manifest = manifest or root / RUNTIME_ARTIFACTS_RELATIVE
    if not manifest.is_file():
        raise ValueError(
            f"resolved runtime artifact manifest is missing: {manifest}; "
            "run ./gradlew :app:writeReleaseRuntimeArtifacts"
        )

    root = root.resolve()
    components: list[dict[str, object]] = []
    coordinates: set[tuple[str, str]] = set()
    local_files: dict[str, str] = {}
    with manifest.open(encoding="utf-8", newline="") as source:
        rows = csv.reader(source, delimiter="\t")
        try:
            header = next(rows)
        except StopIteration as error:
            raise ValueError(
                f"resolved runtime artifact manifest is empty: {manifest}"
            ) from error
        if header != RUNTIME_ARTIFACT_HEADER:
            raise ValueError(
                f"unexpected resolved runtime artifact header in {manifest}"
            )

        for line_number, row in enumerate(rows, 2):
            context = f"{manifest}:{line_number}"
            if len(row) != len(RUNTIME_ARTIFACT_HEADER):
                raise ValueError(f"{context}: expected 6 tab-separated fields")
            kind, group, name, version, raw_path, expected_hash = row
            if not SHA256_RE.fullmatch(expected_hash):
                raise ValueError(f"{context}: invalid artifact SHA-256")

            if kind == "maven":
                if not all((group, name, version)) or "-" in (group, name, version):
                    raise ValueError(f"{context}: incomplete Maven coordinate")
                artifact = Path(raw_path)
                if not artifact.is_absolute():
                    raise ValueError(f"{context}: Maven artifact path must be absolute")
                key = (group, name)
                if key in coordinates:
                    raise ValueError(
                        f"{context}: duplicate resolved coordinate {group}:{name}"
                    )
                coordinates.add(key)
            elif kind == "file":
                if (group, name, version) != ("-", "-", "-"):
                    raise ValueError(
                        f"{context}: local artifact must not claim Maven identity"
                    )
                relative = Path(raw_path)
                if relative.is_absolute() or ".." in relative.parts:
                    raise ValueError(
                        f"{context}: local artifact path must stay inside the repository"
                    )
                artifact = (root / relative).resolve()
                try:
                    artifact.relative_to(root)
                except ValueError as error:
                    raise ValueError(
                        f"{context}: local artifact resolves outside the repository"
                    ) from error
                normalized = relative.as_posix()
                if normalized in local_files:
                    raise ValueError(
                        f"{context}: duplicate local artifact {normalized}"
                    )
            else:
                raise ValueError(f"{context}: unsupported artifact kind {kind!r}")

            if not artifact.is_file():
                raise ValueError(
                    f"{context}: resolved runtime artifact is missing: {artifact}"
                )
            actual_hash = file_sha256(artifact)
            if actual_hash != expected_hash:
                raise ValueError(
                    f"{context}: resolved runtime artifact hash changed: "
                    f"expected {expected_hash}, got {actual_hash}"
                )

            if kind == "file":
                local_files[normalized] = actual_hash
                continue

            purl = (
                f"pkg:maven/{quote(group, safe='.')}/{quote(name, safe='.-_')}"
                f"@{quote(version, safe='.-_')}"
            )
            components.append(
                {
                    "type": "library",
                    "bom-ref": purl,
                    "group": group,
                    "name": name,
                    "version": version,
                    "purl": purl,
                    "hashes": [{"alg": "SHA-256", "content": actual_hash}],
                }
            )

    components.sort(key=lambda component: str(component["bom-ref"]))
    return components, local_files


def maven_components(
    root: Path, manifest: Path | None = None
) -> list[dict[str, object]]:
    """Compatibility wrapper for callers that only need resolved Maven artifacts."""
    return resolved_runtime_artifacts(root, manifest)[0]


def runtime_components(root: Path, manifest: Path) -> list[dict[str, object]]:
    components: list[dict[str, object]] = []
    with manifest.open(encoding="utf-8", newline="") as source:
        rows = csv.reader(source, delimiter="\t")
        for line_number, row in enumerate(rows, 1):
            if not row or row[0].startswith("#"):
                continue
            if len(row) != 8:
                raise ValueError(
                    f"{manifest}:{line_number}: expected 8 tab-separated fields"
                )
            (
                kind,
                name,
                version,
                purl,
                license_name,
                relative,
                expected_hash,
                source_url,
            ) = row
            component: dict[str, object] = {
                "type": kind,
                "bom-ref": purl,
                "name": name,
                "version": version,
                "purl": purl,
                "licenses": [{"license": {"name": license_name}}],
                "externalReferences": [{"type": "distribution", "url": source_url}],
            }
            if relative != "-" or expected_hash != "-":
                if relative == "-" or not SHA256_RE.fullmatch(expected_hash):
                    raise ValueError(
                        f"{manifest}:{line_number}: path and SHA-256 must be paired"
                    )
                artifact = root / relative
                if not artifact.exists():
                    raise ValueError(
                        f"required runtime artifact is missing: {relative}"
                    )
                if artifact.is_file():
                    actual_hash = file_sha256(artifact)
                    properties = [{"name": "slide:repository-path", "value": relative}]
                elif artifact.is_dir():
                    actual_hash, file_count = directory_tree_sha256(artifact)
                    if file_count == 0:
                        raise ValueError(
                            f"runtime component directory is empty: {relative}"
                        )
                    properties = [
                        {"name": "slide:repository-path", "value": relative},
                        {
                            "name": "slide:tree-hash-format",
                            "value": "slide-tree-sha256-v1(path-nul-size-nul-content)",
                        },
                        {"name": "slide:tree-file-count", "value": str(file_count)},
                    ]
                else:
                    raise ValueError(
                        f"runtime component path is not a file or directory: {relative}"
                    )
                if actual_hash != expected_hash:
                    raise ValueError(
                        f"runtime artifact hash changed for {relative}: "
                        f"expected {expected_hash}, got {actual_hash}"
                    )
                component["hashes"] = [{"alg": "SHA-256", "content": actual_hash}]
                component["properties"] = properties
            components.append(component)
    return components


def build_bom(
    root: Path,
    version: str,
    source_commit: str,
    manifest: Path,
    artifacts_manifest: Path | None = None,
) -> dict[str, object]:
    if not version or any(character.isspace() for character in version):
        raise ValueError("version must be a non-empty token")
    if not COMMIT_RE.fullmatch(source_commit):
        raise ValueError("source commit must be a lowercase 40-character Git object ID")
    # Package URL's `apk` type means Alpine Linux packages, not Android applications.
    app_purl = f"pkg:generic/com.slide@{quote(version, safe='.-_')}"
    maven, resolved_files = resolved_runtime_artifacts(root, artifacts_manifest)
    reviewed = runtime_components(root, manifest)

    reviewed_files: dict[str, str] = {}
    reviewed_classpath_files: set[str] = set()
    for component in reviewed:
        properties = component.get("properties", [])
        repository_paths = [
            str(item["value"])
            for item in properties
            if item.get("name") == "slide:repository-path"
        ]
        if not repository_paths:
            continue
        if len(repository_paths) != 1:
            raise ValueError(
                f"component has multiple repository paths: {component['name']}"
            )
        hashes = component.get("hashes", [])
        if len(hashes) != 1 or hashes[0].get("alg") != "SHA-256":
            raise ValueError(
                f"component lacks one reviewed SHA-256: {component['name']}"
            )
        relative = repository_paths[0]
        reviewed_files[relative] = str(hashes[0]["content"])
        if component["type"] == "library" and Path(relative).suffix in {".aar", ".jar"}:
            reviewed_classpath_files.add(relative)

    if set(resolved_files) != reviewed_classpath_files:
        missing_review = sorted(set(resolved_files) - reviewed_classpath_files)
        missing_runtime = sorted(reviewed_classpath_files - set(resolved_files))
        raise ValueError(
            "reviewed local runtime artifacts do not match Gradle's selection: "
            f"unreviewed={missing_review}, not-selected={missing_runtime}"
        )
    for relative, resolved_hash in resolved_files.items():
        if reviewed_files[relative] != resolved_hash:
            raise ValueError(
                f"reviewed local runtime hash disagrees with Gradle: {relative}"
            )

    components = maven + reviewed
    components.sort(key=lambda component: str(component["bom-ref"]))
    references = [str(component["bom-ref"]) for component in components]
    if len(references) != len(set(references)):
        raise ValueError("SBOM contains duplicate component references")
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": (
            "urn:uuid:"
            + str(
                uuid.uuid5(
                    uuid.NAMESPACE_URL,
                    f"{app_purl}?source={source_commit}",
                )
            )
        ),
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": app_purl,
                "name": "Slide",
                "version": version,
                "purl": app_purl,
            },
            "properties": [{"name": "slide:source-commit", "value": source_commit}],
        },
        "components": components,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("source_commit")
    parser.add_argument("output", type=Path)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--components", type=Path, default=COMPONENTS_FILE)
    parser.add_argument("--runtime-artifacts", type=Path)
    args = parser.parse_args()

    bom = build_bom(
        args.root.resolve(),
        args.version,
        args.source_commit,
        args.components.resolve(),
        args.runtime_artifacts.resolve() if args.runtime_artifacts else None,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(bom, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"Wrote CycloneDX SBOM with {len(bom['components'])} components: {args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
