#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/third_party" "$ROOT/app/src/main/assets/models"

if [ ! -d "$ROOT/third_party/whisper.cpp/.git" ]; then
  git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git "$ROOT/third_party/whisper.cpp"
fi

# v0.22: Serbian-fine-tuned Whisper Small, already converted and quantized
# for whisper.cpp. Remove the generic multilingual Base model so it is not
# accidentally bundled into the APK together with the Serbian model.
rm -f "$ROOT/app/src/main/assets/models/ggml-base-q5_1.bin"

MODEL="$ROOT/app/src/main/assets/models/ggml-whisper-small-sr-q5_0.bin"
if [ ! -f "$MODEL" ]; then
  curl -L --fail --retry 4 --retry-delay 3     "https://huggingface.co/Sagicc/Whisper.cpp/resolve/main/ggml-whisper-small-sr-q5_0.bin?download=true"     -o "$MODEL"
fi

# The published q5_0 file is ~175 MB. A tiny/truncated file must fail the build.
SIZE="$(wc -c < "$MODEL")"
if [ "$SIZE" -lt 150000000 ]; then
  echo "Serbian Whisper model download is incomplete: $SIZE bytes" >&2
  exit 1
fi

echo "Whisper source i Serbian Small q5_0 model su spremni."
