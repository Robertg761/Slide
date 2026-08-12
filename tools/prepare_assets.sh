#!/usr/bin/env bash
# Materialise every authenticated, gitignored binary required by a fresh Slide checkout.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash "$ROOT/tools/fetch_model.sh" base.en-q5_1
bash "$ROOT/tools/fetch_executorch.sh"
bash "$ROOT/tools/fetch_swipe_models.sh"

echo "Prepared Slide speech, neural runtime, and swipe-model assets."
