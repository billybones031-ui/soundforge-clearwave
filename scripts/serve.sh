#!/usr/bin/env bash
# Serves the progress dashboard on http://localhost:8765
# Regenerates the HTML every 30 seconds so the auto-refresh always gets fresh data.
#
# Usage:
#   ./scripts/serve.sh
#   ./scripts/serve.sh 9000   # custom port

set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${1:-8765}"

echo "▶ Generating initial dashboard..."
bash "$REPO/scripts/progress.sh"

echo "▶ Starting server on http://localhost:$PORT"
echo "  Pin this tab in your browser — it auto-refreshes every 30 s"
echo "  Press Ctrl+C to stop"
echo ""

# Serve in background
cd "$REPO/progress"
python3 -m http.server "$PORT" --bind 0.0.0.0 > /dev/null 2>&1 &
SERVER_PID=$!

# Regenerate on a loop
trap "kill $SERVER_PID 2>/dev/null; echo 'Stopped.'; exit 0" INT TERM

while true; do
  sleep 30
  bash "$REPO/scripts/progress.sh" > /dev/null
done
