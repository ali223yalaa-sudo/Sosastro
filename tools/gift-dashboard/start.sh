#!/usr/bin/env bash
# Start the gift authoring dashboard, detached from any agent session.
#
# The dashboard is the *authoring* half of the gift system: it turns a phone
# video into an alpha clip and lets the operator publish it. It must keep
# running with no OpenHands conversation attached, so it is launched with
# setsid and its own log, and `watchdog.sh` brings it back after a restart.
#
# Env overrides: GIFT_PORT (12000), GIFT_ALT_PORT (12001), GIFT_TOOLS.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
PYTHON="${GIFT_PYTHON:-/workspace/venv/bin/python}"
PORT="${GIFT_PORT:-12000}"
ALT_PORT="${GIFT_ALT_PORT:-12001}"
LOG="${GIFT_LOG:-/workspace/giftdash.log}"

export GIFT_TOOLS="${GIFT_TOOLS:-/workspace/svga_tools}"

# Already answering? Do nothing — a second bind would fail and spam the log.
if curl -sf -m 3 -o /dev/null "http://127.0.0.1:${PORT}/"; then
  echo "gift dashboard already up on :${PORT}"
  exit 0
fi

cd "$HERE"
setsid nohup "$PYTHON" server.py --port "$PORT" --also-port "$ALT_PORT" \
  >>"$LOG" 2>&1 &
echo "gift dashboard starting on :${PORT} and :${ALT_PORT} (log: $LOG)"
