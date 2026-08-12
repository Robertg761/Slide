#!/usr/bin/env bash
#
# Produces a pinned ExecuTorch Android AAR with boolean input-tensor support. ExecuTorch 1.2 can
# execute bool tensors natively, but its Java Tensor factory omits that dtype. The source patch is
# deliberately confined to the Java wrapper; the official native runtime is left byte-for-byte
# unchanged.
set -euo pipefail

VERSION="1.2.0"
FBJNI_VERSION="0.7.0"
OUTPUT_SHA256="9752979140e20abb2f7ec24d6ec85ff2b5614c4b4ab916da68d1b71e501bca8e"
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
stage_dir=""
staged=""
cleanup() {
    rm -rf "$work"
    [[ -z "$stage_dir" ]] || rm -rf "$stage_dir"
}
trap cleanup EXIT

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

# Upstream's runtime AAR accidentally ships its own AndroidJUnitRunner declaration. A library
# manifest is merged into every consumer, so leaving it in would advertise a test-only class that
# is neither packaged nor targeted at Slide. Remove all instrumentation components before repack.
python3 - "$work/executorch/AndroidManifest.xml" <<'PY'
import sys
import xml.etree.ElementTree as ElementTree

manifest = sys.argv[1]
tree = ElementTree.parse(manifest)
root = tree.getroot()
for child in list(root):
    if child.tag.rsplit("}", 1)[-1] == "instrumentation":
        root.remove(child)
tree.write(manifest, encoding="utf-8", xml_declaration=True)
PY
if grep -q '<instrumentation' "$work/executorch/AndroidManifest.xml"; then
    echo "ExecuTorch instrumentation could not be removed from the runtime manifest." >&2
    exit 1
fi

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
stage_dir="$(mktemp -d "$DEST_DIR/.executorch-android-$VERSION-slide.XXXXXX")"
staged="$stage_dir/output.aar"
(
    cd "$work/executorch"
    find . -type f -printf '%P\n' | LC_ALL=C sort | zip -q -X -9 "$staged" -@
)

printf '%s  %s\n' "$OUTPUT_SHA256" "$staged" | sha256sum --check --status || {
    echo "Generated ExecuTorch runtime is not the reviewed reproducible artifact." >&2
    exit 1
}
# Same-directory rename is atomic: an interrupted or unreviewed rebuild never replaces the last
# validated runtime that Gradle consumes, and concurrent fetchers each have their own staging file.
mv -f "$staged" "$DEST"
rm -rf "$stage_dir"
stage_dir=""
staged=""

echo "Wrote patched ExecuTorch runtime: $DEST ($(du -h "$DEST" | cut -f1))"
