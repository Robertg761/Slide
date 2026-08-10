#!/usr/bin/env bash
#
# Produces a pinned ExecuTorch Android AAR with boolean input-tensor support. ExecuTorch 1.2 can
# execute bool tensors natively, but its Java Tensor factory omits that dtype. The source patch is
# deliberately confined to the Java wrapper; the official native runtime is left byte-for-byte
# unchanged.
set -euo pipefail

VERSION="1.2.0"
FBJNI_VERSION="0.7.0"
MAVEN="https://repo1.maven.org/maven2"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/third_party/executorch"
DEST="$DEST_DIR/executorch-android-$VERSION-slide.aar"

ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_SDK" && -f "$ROOT/local.properties" ]]; then
    ANDROID_SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -n 1)"
fi
ANDROID_JAR="$ANDROID_SDK/platforms/android-37.0/android.jar"
if [[ ! -f "$ANDROID_JAR" ]]; then
    echo "Android 37 SDK is required to patch the ExecuTorch Java wrapper: $ANDROID_JAR" >&2
    exit 1
fi

mkdir -p "$DEST_DIR"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fetch() {
    local url="$1"
    local destination="$2"
    local expected="$3"
    curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --silent --show-error \
        "$url" -o "$destination"
    printf '%s  %s\n' "$expected" "$destination" | sha256sum --check --status || {
        echo "Dependency failed SHA-256 verification: $url" >&2
        exit 1
    }
}

fetch \
    "$MAVEN/org/pytorch/executorch-android/$VERSION/executorch-android-$VERSION.aar" \
    "$work/executorch.aar" \
    "e5c0a274ac289e31538ff258bf5f13d58cc90bc94b449a40e210ce0e514c2459"
fetch \
    "$MAVEN/org/pytorch/executorch-android/$VERSION/executorch-android-$VERSION-sources.jar" \
    "$work/executorch-sources.jar" \
    "c1db092b85b7dcab7287946e65d5e4e3171853bf60c2e8c79c25d90ecf2e7db8"
fetch \
    "$MAVEN/com/facebook/fbjni/fbjni/$FBJNI_VERSION/fbjni-$FBJNI_VERSION.aar" \
    "$work/fbjni.aar" \
    "7e319ae110ac5e5ef18904170aea5c3e753e915d196699d7fd39d36c8e1dfe36"

mkdir -p \
    "$work/source/org/pytorch/executorch" \
    "$work/executorch" \
    "$work/fbjni" \
    "$work/classes" \
    "$work/classes-jar"
unzip -q "$work/executorch.aar" -d "$work/executorch"
unzip -q "$work/fbjni.aar" classes.jar -d "$work/fbjni"
unzip -p "$work/executorch-sources.jar" org/pytorch/executorch/Tensor.java \
    > "$work/source/org/pytorch/executorch/Tensor.java"
patch --quiet -d "$work/source" -p1 < "$DEST_DIR/tensor-bool.patch"

javac -source 8 -target 8 -Xlint:-options \
    -classpath "$ANDROID_JAR:$work/executorch/classes.jar:$work/fbjni/classes.jar" \
    -d "$work/classes" \
    "$work/source/org/pytorch/executorch/Tensor.java"

unzip -q "$work/executorch/classes.jar" -d "$work/classes-jar"
cp -f "$work/classes/org/pytorch/executorch"/Tensor*.class \
    "$work/classes-jar/org/pytorch/executorch/"
find "$work/classes-jar" -exec touch -d '@0' {} +
rm -f "$work/executorch/classes.jar"
(
    cd "$work/classes-jar"
    find . -type f -printf '%P\n' | LC_ALL=C sort \
        | zip -q -X -9 "$work/executorch/classes.jar" -@
)

# Repack from a stable file order and timestamp so the locally produced runtime is reproducible.
find "$work/executorch" -exec touch -d '@0' {} +
rm -f "$DEST"
(
    cd "$work/executorch"
    find . -type f -printf '%P\n' | LC_ALL=C sort | zip -q -X -9 "$DEST" -@
)

echo "Wrote patched ExecuTorch runtime: $DEST ($(du -h "$DEST" | cut -f1))"
