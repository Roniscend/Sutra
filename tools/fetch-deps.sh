#!/usr/bin/env bash

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$HERE"

WHISPER_REPO="https://github.com/ggml-org/whisper.cpp"
WHISPER_DIR="vendor/whisper.cpp"
MODEL_PATH="app/src/main/assets/models/ggml-whisper.bin"

WHISPER_REV="c122757fddf358397bb7f33b6ac3aab24a5bca04"

if [ -d "$WHISPER_DIR/src" ]; then
  have="$(git -C "$WHISPER_DIR" rev-parse HEAD 2>/dev/null || echo none)"
  if [ "$have" = "$WHISPER_REV" ]; then
    echo "whisper.cpp already at the pinned revision"
  else
    echo "whisper.cpp is at $have, moving it to the pinned $WHISPER_REV"
    git -C "$WHISPER_DIR" fetch --depth 1 origin "$WHISPER_REV"
    git -C "$WHISPER_DIR" checkout -q --detach FETCH_HEAD
  fi
else
  echo "cloning whisper.cpp into $WHISPER_DIR at $WHISPER_REV"
  mkdir -p vendor
  git init -q "$WHISPER_DIR"
  git -C "$WHISPER_DIR" remote add origin "$WHISPER_REPO"
  git -C "$WHISPER_DIR" fetch --depth 1 origin "$WHISPER_REV"
  git -C "$WHISPER_DIR" checkout -q --detach FETCH_HEAD
fi

if [ ! -f "$MODEL_PATH" ]; then
  echo "warning: $MODEL_PATH is missing. It is tracked in git — check out the" >&2
  echo "working tree fully, or the field phone will report 'model unavailable'." >&2
fi

cat <<'EOF'

Dependencies are in place. Next:

  cp server/.env.example server/.env.local
  ./gradlew :app:assembleDebug

The arm64-v8a APK carries the speech model; the x86_64 one does not, because
the control room never runs the recogniser.
EOF
