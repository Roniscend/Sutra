#!/usr/bin/env bash

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$HERE"

WHISPER_REPO="https://github.com/ggml-org/whisper.cpp"
WHISPER_DIR="vendor/whisper.cpp"
MODEL_PATH="app/src/main/assets/models/ggml-whisper.bin"

if [ -d "$WHISPER_DIR/src" ]; then
  echo "whisper.cpp already present at $WHISPER_DIR"
else
  echo "cloning whisper.cpp into $WHISPER_DIR"
  mkdir -p vendor
  git clone --depth 1 "$WHISPER_REPO" "$WHISPER_DIR"
fi

if [ ! -f "$MODEL_PATH" ]; then
  echo "warning: $MODEL_PATH is missing. It is tracked in git — check out the" >&2
  echo "working tree fully, or the field phone will report 'model unavailable'." >&2
fi

cat <<'EOF'

Dependencies are in place. Next:

  cp server/.env.example server/.env.local   # add your Agora and Groq keys
  ./gradlew :app:assembleDebug

The arm64-v8a APK carries the speech model; the x86_64 one does not, because
the control room never runs the recogniser.
EOF
