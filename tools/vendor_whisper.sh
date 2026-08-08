#!/usr/bin/env bash
#
# Re-vendors whisper.cpp into third_party/.
#
# The source is committed rather than fetched at build time: Slide builds offline, and a keyboard
# that silently picks up a different speech engine between builds is not something anyone wants to
# debug. Bumping the version is therefore a deliberate act -- change PINNED_COMMIT, run this, and
# re-run the ASR tests.
#
# Everything that cannot apply to an Android build is deleted, so what remains is roughly what we
# actually compile. Backends that a phone could plausibly use one day (OpenCL, Vulkan, Hexagon) are
# kept even though the build only enables CPU today.
set -euo pipefail

REPO="https://github.com/ggml-org/whisper.cpp"
PINNED_COMMIT="592feef04a1802b18cbeffd0fd0eb5d02570c2ec"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/whisper.cpp"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Fetching $PINNED_COMMIT"
git init -q "$WORK/whisper.cpp"
git -C "$WORK/whisper.cpp" remote add origin "$REPO"
git -C "$WORK/whisper.cpp" fetch -q --depth 1 origin "$PINNED_COMMIT"
git -C "$WORK/whisper.cpp" checkout -q FETCH_HEAD

cd "$WORK/whisper.cpp"

# History, other-language bindings, sample media, and the example programs. None are compiled.
rm -rf .git .github bindings examples tests samples media ci scripts grammars
rm -f close-issue.yml README_sycl.md build-xcframework.sh CMakePresets.json Makefile

# Prebuilt models; Slide fetches its own (see tools/fetch_model.sh).
rm -f models/*.bin models/*.mlmodelc

# Backends for hardware Android does not have.
for backend in cuda sycl cann musa hip metal webgpu zdnn zendnn openvino virtgpu blas et rpc; do
    rm -rf "ggml/src/ggml-$backend"
done
rm -rf src/coreml src/openvino

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$WORK/whisper.cpp" "$DEST"

echo "$PINNED_COMMIT" > "$DEST/VENDORED_COMMIT"
echo "Vendored $(du -sh "$DEST" | cut -f1) to $DEST"
