#!/usr/bin/env bash

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_LOG="${TMPDIR:-/tmp}/sutra-server.log"
TUNNEL_LOG="${TMPDIR:-/tmp}/sutra-tunnel.log"
PORT=8000

cd "$HERE"

if [ ! -f server/.env.local ]; then
  echo "server/.env.local is missing. It needs AGORA_APP_ID, AGORA_APP_CERTIFICATE and GROQ_API_KEY." >&2
  exit 1
fi

PYTHON=server/.venv/Scripts/python.exe
[ -x "$PYTHON" ] || PYTHON=server/.venv/bin/python
if [ ! -x "$PYTHON" ]; then
  echo "No virtualenv at server/.venv — create it and install server/requirements-dev.txt." >&2
  exit 1
fi

existing="$(netstat -ano 2>/dev/null | grep ":$PORT " | grep LISTENING | head -1 | awk '{print $NF}' || true)"
if [ -n "$existing" ]; then
  echo "stopping the server already on :$PORT (pid $existing)"
  taskkill //F //PID "$existing" >/dev/null 2>&1 || kill "$existing" 2>/dev/null || true
  sleep 2
fi

echo "starting backend -> $SERVER_LOG"
(cd server && "../$PYTHON" -m uvicorn app.main:app --host 127.0.0.1 --port "$PORT" > "$SERVER_LOG" 2>&1 &)
until curl -s -m 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; do sleep 1; done
echo "backend healthy: $(curl -s "http://127.0.0.1:$PORT/health")"

pkill -f "cloudflared tunnel" 2>/dev/null || true
rm -f "$TUNNEL_LOG"
echo "starting tunnel -> $TUNNEL_LOG"
(PATH="$PATH:/c/Program Files (x86)/cloudflared:/c/Program Files/cloudflared" \
  cloudflared tunnel --url "http://127.0.0.1:$PORT" --no-autoupdate > "$TUNNEL_LOG" 2>&1 &)

URL=""
for _ in $(seq 1 30); do
  sleep 2
  URL="$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$TUNNEL_LOG" | head -1 || true)"
  [ -n "$URL" ] && break
done
if [ -z "$URL" ]; then
  echo "the tunnel did not report a URL; see $TUNNEL_LOG" >&2
  exit 1
fi
echo "public URL: $URL"

python - "$URL" <<'PY'
import pathlib, re, sys
url = sys.argv[1]
env = pathlib.Path("server/.env.local")
text = env.read_text()
text = re.sub(r"PUBLIC_BASE_URL=.*\n?", "", text).rstrip() + f"\nPUBLIC_BASE_URL={url}\n"
env.write_text(text)

local = pathlib.Path("local.properties")
text = local.read_text() if local.exists() else ""
text = re.sub(r"QUICKSTART_SERVER_URL=.*\n?", "", text).rstrip() + f"\nQUICKSTART_SERVER_URL={url}\n"
local.write_text(text.lstrip("\n"))
print("wrote PUBLIC_BASE_URL and QUICKSTART_SERVER_URL")
PY

existing="$(netstat -ano 2>/dev/null | grep ":$PORT " | grep LISTENING | head -1 | awk '{print $NF}' || true)"
[ -n "$existing" ] && { taskkill //F //PID "$existing" >/dev/null 2>&1 || kill "$existing" 2>/dev/null || true; sleep 2; }
(cd server && "../$PYTHON" -m uvicorn app.main:app --host 127.0.0.1 --port "$PORT" > "$SERVER_LOG" 2>&1 &)
until curl -s -m 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; do sleep 1; done
echo "backend restarted with tools pointing at $URL"

if [ "${1:-}" = "--build" ]; then
  echo "building the APK with the new server URL"
  JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}" ./gradlew :app:assembleDebug --console=plain
  echo "APK: app/build/outputs/apk/debug/app-debug.apk"
fi

cat <<EOF

Ready.
  backend   http://127.0.0.1:$PORT   (log: $SERVER_LOG)
  public    $URL                     (log: $TUNNEL_LOG)

Install the APK on both phones, use the same channel name on each, and pick
field phone on the weak-link device and control room on the other.
EOF
