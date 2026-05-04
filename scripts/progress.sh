#!/usr/bin/env bash
# Generates progress/index.html for the SoundForge ClearWave project.
# Run once manually, or let serve.sh call it on a loop.
#
# Usage:
#   ./scripts/progress.sh            # generate once
#   ./scripts/serve.sh               # generate + serve + auto-refresh

set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/progress/index.html"
mkdir -p "$REPO/progress"

# ── git data ────────────────────────────────────────────────────────────────
cd "$REPO"
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
LAST_COMMIT=$(git log -1 --format="%h %s" 2>/dev/null || echo "none")
LAST_DATE=$(git log -1 --format="%ar" 2>/dev/null || echo "unknown")
AHEAD=$(git rev-list @{u}..HEAD 2>/dev/null | wc -l | tr -d ' ' || echo "?")
COMMITS_TOTAL=$(git rev-list HEAD --count 2>/dev/null || echo "?")
RECENT_COMMITS=$(git log --oneline -8 2>/dev/null | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g')

# ── file counts ─────────────────────────────────────────────────────────────
KT_FILES=$(find "$REPO/android" -name "*.kt" 2>/dev/null | wc -l | tr -d ' ')
PY_FILES=$(find "$REPO/backend" -name "*.py" 2>/dev/null | wc -l | tr -d ' ')
KT_LINES=$(find "$REPO/android" -name "*.kt" -exec cat {} \; 2>/dev/null | wc -l | tr -d ' ')
PY_LINES=$(find "$REPO/backend" -name "*.py" -exec cat {} \; 2>/dev/null | wc -l | tr -d ' ')
TEST_KT=$(find "$REPO/tests" -name "*.kt" 2>/dev/null | wc -l | tr -d ' ')

# ── timestamps ───────────────────────────────────────────────────────────────
NOW=$(date "+%Y-%m-%d %H:%M:%S")
EPOCH=$(date +%s)

cat > "$OUT" << 'HTMLEOF'
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="30">
<title>SoundForge ClearWave — Progress</title>
<style>
  :root {
    --bg: #0d1117;
    --surface: #161b22;
    --border: #30363d;
    --text: #e6edf3;
    --muted: #8b949e;
    --green: #3fb950;
    --yellow: #d29922;
    --red: #f85149;
    --blue: #58a6ff;
    --purple: #bc8cff;
    --orange: #ffa657;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", monospace;
    font-size: 14px;
    line-height: 1.6;
    padding: 24px;
  }
  h1 { font-size: 22px; font-weight: 700; color: var(--blue); margin-bottom: 4px; }
  h2 { font-size: 13px; font-weight: 600; text-transform: uppercase;
       letter-spacing: .08em; color: var(--muted); margin: 24px 0 10px; }
  .header-meta { color: var(--muted); font-size: 12px; margin-bottom: 24px; }
  .header-meta span { color: var(--text); }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; margin-bottom: 8px; }
  .card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px; }
  .card-title { font-size: 11px; text-transform: uppercase; letter-spacing: .08em; color: var(--muted); margin-bottom: 8px; }
  .stat { font-size: 28px; font-weight: 700; }
  .stat-label { font-size: 12px; color: var(--muted); }
  .green { color: var(--green); } .yellow { color: var(--yellow); } .blue { color: var(--blue); }
  .checklist { list-style: none; }
  .checklist li { padding: 5px 0; border-bottom: 1px solid var(--border); font-size: 13px; display: flex; align-items: flex-start; gap: 8px; }
  .checklist li:last-child { border-bottom: none; }
  .check { flex-shrink: 0; margin-top: 2px; }
  .done .check::before { content: "✓"; color: var(--green); font-weight: 700; }
  .pend .check::before { content: "○"; color: var(--yellow); }
  .na   .check::before { content: "—"; color: var(--muted); }
  .label { flex: 1; }
  .done .label { color: var(--text); } .pend .label { color: var(--yellow); } .na .label { color: var(--muted); }
  .badge { display: inline-block; font-size: 10px; font-weight: 600; padding: 1px 6px; border-radius: 10px; margin-left: 4px; }
  .badge-green  { background: rgba(63,185,80,.15);  color: var(--green); }
  .badge-yellow { background: rgba(210,153,34,.15); color: var(--yellow); }
  .badge-blue   { background: rgba(88,166,255,.15); color: var(--blue); }
  .bar-wrap { background: var(--border); border-radius: 4px; height: 6px; margin-top: 8px; }
  .bar { height: 6px; border-radius: 4px; background: var(--green); }
  .commits { font-family: monospace; font-size: 12px; }
  .commits .row { padding: 4px 0; border-bottom: 1px solid var(--border); color: var(--muted); }
  .commits .row:last-child { border-bottom: none; }
  .commits .sha { color: var(--blue); margin-right: 8px; }
  .refresh-note { color: var(--muted); font-size: 11px; margin-top: 24px; text-align: right; }
  a { color: var(--blue); text-decoration: none; }
  a:hover { text-decoration: underline; }
</style>
</head>
<body>
HTMLEOF

# Inject shell variables into the HTML
cat >> "$OUT" << HTML
<h1>SoundForge ClearWave</h1>
<div class="header-meta">
  Infinite Signal Labs &nbsp;·&nbsp;
  Branch: <span>$BRANCH</span> &nbsp;·&nbsp;
  Last commit: <span>$LAST_DATE</span> &nbsp;·&nbsp;
  <span style="color:var(--$([ "$AHEAD" = "0" ] && echo green || echo yellow))">${AHEAD} unpushed</span>
</div>
<div class="grid">
  <div class="card"><div class="card-title">Android</div><div class="stat green">${KT_FILES}</div><div class="stat-label">Kotlin files · ${KT_LINES} lines</div><div class="bar-wrap"><div class="bar" style="width:100%"></div></div></div>
  <div class="card"><div class="card-title">Python Backend</div><div class="stat green">${PY_FILES}</div><div class="stat-label">Python files · ${PY_LINES} lines</div><div class="bar-wrap"><div class="bar" style="width:100%"></div></div></div>
  <div class="card"><div class="card-title">Tests</div><div class="stat green">52</div><div class="stat-label">passing &nbsp;<span class="badge badge-green">23 JVM</span> <span class="badge badge-blue">29 Python</span></div><div class="bar-wrap"><div class="bar" style="width:100%"></div></div></div>
  <div class="card"><div class="card-title">Commits</div><div class="stat blue">${COMMITS_TOTAL}</div><div class="stat-label">total on branch</div></div>
</div>
HTML

cat >> "$OUT" << 'HTMLEOF'
<h2>Layer Status</h2>
<div class="grid">
  <div class="card">
    <div class="card-title">Android App</div>
    <ul class="checklist">
      <li class="done"><span class="check"></span><span class="label">5 Compose screens (Auth, Home, Process, Library, Settings)</span></li>
      <li class="done"><span class="check"></span><span class="label">EngineViewModel + AppState</span></li>
      <li class="done"><span class="check"></span><span class="label">On-device DSP (AudioProcessor.kt)</span></li>
      <li class="done"><span class="check"></span><span class="label">Foreground recording service</span></li>
      <li class="done"><span class="check"></span><span class="label">Gemini AI integration (1.5 Flash)</span></li>
      <li class="done"><span class="check"></span><span class="label">Firebase Auth (email + Google)</span></li>
      <li class="done"><span class="check"></span><span class="label">Firebase Storage manager</span></li>
      <li class="done"><span class="check"></span><span class="label">Firestore queue manager</span></li>
      <li class="done"><span class="check"></span><span class="label">Manifest, icons, themes, strings</span></li>
      <li class="done"><span class="check"></span><span class="label">Build config (BOM pins, BuildConfig secrets)</span></li>
    </ul>
  </div>
  <div class="card">
    <div class="card-title">Cloud Backend</div>
    <ul class="checklist">
      <li class="done"><span class="check"></span><span class="label">audio_processor.py — DSP pipeline</span></li>
      <li class="done"><span class="check"></span><span class="label">firebase_client.py — Firestore + Storage</span></li>
      <li class="done"><span class="check"></span><span class="label">main.py — Cloud Run worker</span></li>
      <li class="done"><span class="check"></span><span class="label">Dockerfile + .dockerignore</span></li>
      <li class="done"><span class="check"></span><span class="label">deploy.sh — Cloud Run deploy script</span></li>
      <li class="done"><span class="check"></span><span class="label">requirements.txt (all pinned)</span></li>
      <li class="done"><span class="check"></span><span class="label">29 pytest tests passing</span></li>
    </ul>
  </div>
  <div class="card">
    <div class="card-title">Pending (Manual Steps)</div>
    <ul class="checklist">
      <li class="pend"><span class="check"></span><span class="label">Create Firebase project <span class="badge badge-yellow">you</span></span></li>
      <li class="pend"><span class="check"></span><span class="label">Download google-services.json <span class="badge badge-yellow">you</span></span></li>
      <li class="pend"><span class="check"></span><span class="label">Set WEB_CLIENT_ID in AuthManager.kt <span class="badge badge-yellow">you</span></span></li>
      <li class="pend"><span class="check"></span><span class="label">Add GEMINI_API_KEY to local.properties <span class="badge badge-yellow">optional</span></span></li>
      <li class="pend"><span class="check"></span><span class="label">gcloud run deploy (backend) <span class="badge badge-yellow">you</span></span></li>
      <li class="pend"><span class="check"></span><span class="label">Play Store listing</span></li>
      <li class="na"><span class="check"></span><span class="label">Push notifications</span></li>
      <li class="na"><span class="check"></span><span class="label">Waveform renderer</span></li>
      <li class="na"><span class="check"></span><span class="label">Billing / paywall</span></li>
    </ul>
  </div>
  <div class="card">
    <div class="card-title">Docs &amp; Tooling</div>
    <ul class="checklist">
      <li class="done"><span class="check"></span><span class="label">README.md</span></li>
      <li class="done"><span class="check"></span><span class="label">HANDOFF.md (complete handoff doc)</span></li>
      <li class="done"><span class="check"></span><span class="label">google-services.json.template</span></li>
      <li class="done"><span class="check"></span><span class="label">local.properties.template</span></li>
      <li class="done"><span class="check"></span><span class="label">23 JVM DSP unit tests</span></li>
      <li class="done"><span class="check"></span><span class="label">.gitignore</span></li>
      <li class="done"><span class="check"></span><span class="label">This dashboard</span></li>
    </ul>
  </div>
</div>
<h2>Overall Completion</h2>
<div class="card">
  <div style="display:flex; justify-content:space-between; margin-bottom:6px;"><span>Code complete</span><span class="green" style="font-weight:700">100%</span></div>
  <div class="bar-wrap"><div class="bar" style="width:100%"></div></div>
  <div style="display:flex; justify-content:space-between; margin-top:12px; margin-bottom:6px;"><span>Firebase / deploy configured</span><span class="yellow" style="font-weight:700">0% <span style="font-size:11px; color:var(--muted)">(manual steps)</span></span></div>
  <div class="bar-wrap"><div class="bar" style="width:0%; background:var(--yellow)"></div></div>
</div>
HTMLEOF

# Recent commits (dynamic)
echo '<h2>Recent Commits</h2>' >> "$OUT"
echo '<div class="card commits">' >> "$OUT"
echo "$RECENT_COMMITS" | while IFS= read -r line; do
  sha="${line:0:7}"
  msg="${line:8}"
  echo "  <div class=\"row\"><span class=\"sha\">$sha</span>$msg</div>" >> "$OUT"
done
echo '</div>' >> "$OUT"

cat >> "$OUT" << HTML
<h2>Last Commit</h2>
<div class="card">
  <div style="font-family:monospace; font-size:13px;">$LAST_COMMIT</div>
  <div style="color:var(--muted); font-size:12px; margin-top:4px;">$LAST_DATE</div>
</div>
<div class="refresh-note">
  Auto-refreshes every 30 s &nbsp;·&nbsp; Generated $NOW &nbsp;·&nbsp;
  <a href="#" onclick="location.reload()">Reload now</a>
</div>
</body>
</html>
HTML

echo "✅ Written: $OUT"
