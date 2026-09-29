#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/third_party" "$ROOT/app/src/main/assets/models"

if [ ! -d "$ROOT/third_party/whisper.cpp/.git" ]; then
  git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git "$ROOT/third_party/whisper.cpp"
fi

MODEL="$ROOT/app/src/main/assets/models/ggml-base-q5_1.bin"
if [ ! -f "$MODEL" ]; then
  curl -L --fail --retry 3     https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin     -o "$MODEL"
fi

echo "Whisper source i base q5_1 model su spremni."
