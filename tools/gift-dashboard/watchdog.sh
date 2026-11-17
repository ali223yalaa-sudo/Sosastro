#!/usr/bin/env bash
# Keep the gift authoring dashboard alive across crashes and restarts.
#
# Polls the *port* rather than a PID file: PIDs are reused after a sandbox
# restart, so a PID check can report "alive" while nothing is listening. If
# either port stops answering, the whole pair is restarted through start.sh.
#
# Run detached, e.g.:
#   setsid nohup /workspace/astro-backup/tools/gift-dashboard/watchdog.sh \
#     >>/workspace/giftdash.log 2>&1 &
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
PORT="${GIFT_PORT:-12000}"
ALT_PORT="${GIFT_ALT_PORT:-12001}"
INTERVAL="${GIFT_WATCH_INTERVAL:-10}"

while true; do
  if ! curl -sf -m 4 -o /dev/null "http://127.0.0.1:${PORT}/" \
     || ! curl -sf -m 4 -o /dev/null "http://127.0.0.1:${ALT_PORT}/"; then
    echo "$(date -Is) gift dashboard not answering — restarting"
    pkill -f "server.py --port ${PORT}" 2>/dev/null
    sleep 1
    "$HERE/start.sh"
  fi
  sleep "$INTERVAL"
done
