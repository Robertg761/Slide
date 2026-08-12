#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

CHANGELOG="$TEMP_DIR/CHANGELOG.md"
printf '%s\n' \
    '# Changelog' \
    '' \
    '## [0.3.0-alpha.1] - 2026-08-10' \
    '' \
    '- Alpha notes.' > "$CHANGELOG"

if bash "$ROOT/tools/extract_release_notes.sh" 0.3.0 "$CHANGELOG" >/dev/null 2>&1; then
    echo 'A stable version incorrectly matched a prerelease changelog heading.' >&2
    exit 1
fi

printf '%s\n' \
    '# Changelog' \
    '' \
    '## [0.3.0] - 2026-08-10' \
    '' \
    '- Stable notes.' \
    '' \
    '## [0.3.0-alpha.1] - 2026-08-09' \
    '' \
    '- Alpha notes.' > "$CHANGELOG"

NOTES="$(bash "$ROOT/tools/extract_release_notes.sh" 0.3.0 "$CHANGELOG")"
grep -q -- '- Stable notes.' <<< "$NOTES"
if grep -q -- '- Alpha notes.' <<< "$NOTES"; then
    echo 'Release-note extraction crossed into the next version.' >&2
    exit 1
fi

HISTORY="$TEMP_DIR/versions.tsv"
printf '%s\n' \
    '0.2.1 8' \
    '0.2.0 9' > "$HISTORY"
if bash "$ROOT/tools/verify_version_history.sh" 0.2.0 9 "$HISTORY" >/dev/null 2>&1; then
    echo 'A semantic version downgrade passed with a higher versionCode.' >&2
    exit 1
fi

printf '%s\n' \
    '0.2.1 8' \
    '0.3.0-alpha.1 9' \
    '0.3.0 10' > "$HISTORY"
bash "$ROOT/tools/verify_version_history.sh" 0.3.0 10 "$HISTORY" >/dev/null

for fetcher in fetch_model.sh fetch_executorch.sh fetch_swipe_models.sh; do
    grep -Fq "$fetcher" "$ROOT/tools/prepare_assets.sh" || {
        echo "prepare_assets.sh does not invoke $fetcher" >&2
        exit 1
    }
done

# A failed swipe-model transfer must not leave a canonical or partial model behind. The fetcher
# accepts this test-only destination override so the real ignored assets remain untouched.
MOCK_BIN="$TEMP_DIR/mock-bin"
SWIPE_DEST="$TEMP_DIR/swipe-models"
mkdir -p "$MOCK_BIN" "$SWIPE_DEST"
# Dollar-prefixed arguments belong to the generated mock, not this test process.
# shellcheck disable=SC2016
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'while (( $# )); do' \
    '    if [[ "$1" == "-o" ]]; then' \
    '        printf partial > "$2"' \
    '        exit 22' \
    '    fi' \
    '    shift' \
    'done' \
    'exit 2' > "$MOCK_BIN/curl"
chmod +x "$MOCK_BIN/curl"
if PATH="$MOCK_BIN:$PATH" SLIDE_SWIPE_MODEL_DEST_DIR="$SWIPE_DEST" \
    bash "$ROOT/tools/fetch_swipe_models.sh" >/dev/null 2>&1; then
    echo 'A failed swipe-model download unexpectedly succeeded.' >&2
    exit 1
fi
if find "$SWIPE_DEST" -mindepth 1 -print -quit | grep -q .; then
    echo 'A failed swipe-model download left staged or canonical bytes behind.' >&2
    exit 1
fi

grep -Fq 'OUTPUT_SHA256=' "$ROOT/tools/fetch_executorch.sh"
grep -Fq 'root.remove(child)' "$ROOT/tools/fetch_executorch.sh"
# Dollar-prefixed names are literal source text under test.
# shellcheck disable=SC2016
grep -Fq 'mktemp -d "$DEST_DIR/.executorch-android-$VERSION-slide.' \
    "$ROOT/tools/fetch_executorch.sh"
# shellcheck disable=SC2016
grep -Fq 'staged="$stage_dir/output.aar"' "$ROOT/tools/fetch_executorch.sh"
# shellcheck disable=SC2016
grep -Fq 'mv -f "$staged" "$DEST"' "$ROOT/tools/fetch_executorch.sh"
# shellcheck disable=SC2016
if grep -Fq 'rm -f "$DEST"' "$ROOT/tools/fetch_executorch.sh"; then
    echo 'ExecuTorch rebuild deletes the last validated runtime before replacement.' >&2
    exit 1
fi
# shellcheck disable=SC2016
staged_hash_line="$(grep -n '"$OUTPUT_SHA256" "$staged"' \
    "$ROOT/tools/fetch_executorch.sh" | cut -d: -f1)"
# shellcheck disable=SC2016
staged_move_line="$(grep -n 'mv -f "$staged" "$DEST"' \
    "$ROOT/tools/fetch_executorch.sh" | cut -d: -f1)"
[[ -n "$staged_hash_line" && -n "$staged_move_line" && \
    "$staged_hash_line" -lt "$staged_move_line" ]] || {
    echo 'ExecuTorch staging artifact must be verified before atomic replacement.' >&2
    exit 1
}
grep -Fq -- '-keep class org.pytorch.executorch.** { *; }' "$ROOT/app/proguard-rules.pro" || {
    echo 'Release minification does not preserve the prebuilt ExecuTorch JNI boundary.' >&2
    exit 1
}
grep -Fq -- '-keep class com.facebook.jni.** { *; }' "$ROOT/app/proguard-rules.pro" || {
    echo 'Release minification does not preserve the fbjni bridge used by ExecuTorch.' >&2
    exit 1
}
for descriptor in \
    org/pytorch/executorch/EValue \
    org/pytorch/executorch/Module \
    org/pytorch/executorch/Tensor \
    org/pytorch/executorch/extension/asr/AsrModule; do
    grep -Fq "$descriptor" "$ROOT/tools/verify_release_apk.sh" || {
        echo "Release verifier does not require ExecuTorch class $descriptor." >&2
        exit 1
    }
done
# Dollar signs in nested JVM class names are literal.
# shellcheck disable=SC2016
for descriptor in \
    'com/facebook/jni/HybridData$Destructor' \
    com/facebook/jni/HybridClassBase \
    com/facebook/jni/MapIteratorHelper; do
    grep -Fq "$descriptor" "$ROOT/tools/verify_release_apk.sh" || {
        echo "Release verifier does not require fbjni class $descriptor." >&2
        exit 1
    }
done

grep -Fq ':app:writeReleaseRuntimeArtifacts' "$ROOT/.github/workflows/ci.yml" || {
    echo 'CI does not export the exact resolved release runtime artifacts.' >&2
    exit 1
}
if [[ "$(grep -Fc ':app:writeReleaseRuntimeArtifacts' "$ROOT/.github/workflows/release.yml")" -lt 2 ]]; then
    echo 'Release must export runtime artifacts before tests and regenerate them after clean builds.' >&2
    exit 1
fi

# Immutable GitHub releases must remain drafts until every asset is attached. This is especially
# important for prereleases, which the pinned upload action otherwise publishes before uploading.
grep -Fq 'id: staged_release' "$ROOT/.github/workflows/release.yml"
grep -Fq 'draft: true' "$ROOT/.github/workflows/release.yml"
# This is a literal GitHub Actions expression under test.
# shellcheck disable=SC2016
grep -Fq 'RELEASE_ID: ${{ steps.staged_release.outputs.id }}' \
    "$ROOT/.github/workflows/release.yml"
grep -Fq "printf '{\"draft\":false,\"prerelease\":%s}\\n'" \
    "$ROOT/.github/workflows/release.yml"
stage_line="$(grep -n 'id: staged_release' "$ROOT/.github/workflows/release.yml" | cut -d: -f1)"
publish_line="$(grep -n 'name: Publish the fully populated draft' \
    "$ROOT/.github/workflows/release.yml" | cut -d: -f1)"
[[ -n "$stage_line" && -n "$publish_line" && "$stage_line" -lt "$publish_line" ]] || {
    echo 'Release finalization must occur after the staged asset upload.' >&2
    exit 1
}

# Use a real loadable ELF, because the release verifier must reject a file that only forges the
# first bytes of a header as well as inspect e_machine rather than trusting lib/<abi>/.
ELF_FIXTURE="$TEMP_DIR/x86_64.so"
ELF_SOURCE="$(type -P true || true)"
[[ -n "$ELF_SOURCE" ]] || { echo 'Could not locate a real host ELF fixture.' >&2; exit 1; }
cp "$ELF_SOURCE" "$ELF_FIXTURE"
bash "$ROOT/tools/verify_release_apk.sh" --verify-elf-abi "$ELF_FIXTURE" x86_64
if bash "$ROOT/tools/verify_release_apk.sh" --verify-elf-abi "$ELF_FIXTURE" arm64-v8a \
    >/dev/null 2>&1; then
    echo 'An x86-64 ELF image incorrectly passed as arm64-v8a.' >&2
    exit 1
fi
TRUNCATED_ELF="$TEMP_DIR/truncated.so"
head -c 20 "$ELF_FIXTURE" > "$TRUNCATED_ELF"
if bash "$ROOT/tools/verify_release_apk.sh" --verify-elf-abi "$TRUNCATED_ELF" x86_64 \
    >/dev/null 2>&1; then
    echo 'A truncated ELF header incorrectly passed as a complete native library.' >&2
    exit 1
fi

NATIVE_MANIFEST="$TEMP_DIR/native-paths.txt"
bash "$ROOT/tools/verify_release_apk.sh" --print-expected-native-manifest > "$NATIVE_MANIFEST"
MISSING_NATIVE_MANIFEST="$TEMP_DIR/native-paths-missing-libcxx.txt"
grep -v '^lib/armeabi-v7a/libc++_shared[.]so$' "$NATIVE_MANIFEST" > "$MISSING_NATIVE_MANIFEST"
if bash "$ROOT/tools/verify_release_apk.sh" --verify-native-manifest "$MISSING_NATIVE_MANIFEST" \
    >/dev/null 2>&1; then
    echo 'A native manifest missing arm32 libc++ incorrectly passed.' >&2
    exit 1
fi

DATA_MANIFEST="$TEMP_DIR/runtime-data-paths.txt"
bash "$ROOT/tools/verify_release_apk.sh" --print-expected-runtime-data-manifest > "$DATA_MANIFEST"
printf '%s\n' 'assets/unreviewed-model.onnx' >> "$DATA_MANIFEST"
if bash "$ROOT/tools/verify_release_apk.sh" --verify-runtime-data-manifest "$DATA_MANIFEST" \
    >/dev/null 2>&1; then
    echo 'An extra unreviewed model incorrectly passed the runtime-data manifest.' >&2
    exit 1
fi

python3 "$ROOT/tools/test_build_sbom.py"
python3 "$ROOT/tools/test_language_sources.py"
python3 "$ROOT/tools/rebuild_language_assets.py" --check

echo 'Verified release metadata, asset preparation, locked language rebuild, and deterministic SBOM generation.'
