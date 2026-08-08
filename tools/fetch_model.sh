#!/usr/bin/env bash
#
# Downloads a Whisper model into the :asr module's assets, where it is packaged into the APK.
#
# Slide ships the model rather than downloading it on first run: the keyboard is offline by design,
# and voice input that needs a network round trip before it will work the first time is not
# offline. The cost is APK size, which is the trade the brief asked for.
#
#   tools/fetch_model.sh base.en-q5_1      # the shipped default
#   tools/fetch_model.sh small.en-q5_1     # more accurate, slower
#
# Quantised (q5_1) builds are used throughout: they are a third of the size of the float models for
# a difference in word error rate that does not show up in dictation.
set -euo pipefail

MODEL="${1:-base.en-q5_1}"
BASE_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/asr/src/main/assets"
DEST="$DEST_DIR/ggml-$MODEL.bin"

mkdir -p "$DEST_DIR"

if [[ -f "$DEST" ]]; then
    echo "Already present: $DEST ($(du -h "$DEST" | cut -f1))"
    exit 0
fi

echo "Downloading ggml-$MODEL.bin"
curl -fL --progress-bar "$BASE_URL/ggml-$MODEL.bin" -o "$DEST.part"
mv "$DEST.part" "$DEST"

echo "Wrote $DEST ($(du -h "$DEST" | cut -f1))"
