#!/usr/bin/env bash
# After CI passes on main, Render and Vercel deploy that commit on their own. This waits for both to
# serve it and checks they answer. It runs in .github/workflows/deploy-check.yml, and by hand with:
#   SHA=<commit> BACKEND_URL=https://... FRONTEND_URL=https://... GH_TOKEN=... scripts/deploy-check.sh
# It reads only public URLs and the GitHub deployments API; it needs no secrets.
set -euo pipefail

: "${SHA:?the commit to check}"
: "${BACKEND_URL:?the backend's public URL}"
: "${FRONTEND_URL:?the frontend's public URL}"
: "${GITHUB_REPOSITORY:?owner/name, set by Actions}"
WAIT_MINUTES="${WAIT_MINUTES:-20}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"
deadline=$(( $(date +%s) + WAIT_MINUTES * 60 ))
short="${SHA:0:7}"

fail() {
  echo "::error::$1"
  echo "| Result | **Failed**: $1 |" >> "$SUMMARY"
  exit 1
}

{
  echo "## Deploy check for \`$short\`"
  echo
  echo "| | |"
  echo "| --- | --- |"
} >> "$SUMMARY"

# 1. Backend. Render redeploys only when the files it builds change, so the backend is current once the
#    commit it serves has the same backend/ tree as this one.
want_tree=$(git rev-parse "$SHA:backend")
backend_commit=""
while :; do
  backend_commit=$(curl -fsS --max-time 30 "$BACKEND_URL/actuator/info" | jq -r '.app.commit // empty' || true)
  if [ -n "$backend_commit" ] && git cat-file -e "$backend_commit^{commit}" 2>/dev/null \
      && [ "$(git rev-parse "$backend_commit:backend")" = "$want_tree" ]; then
    break
  fi
  [ "$(date +%s)" -lt "$deadline" ] || fail "the backend still serves ${backend_commit:-nothing} after $WAIT_MINUTES min"
  echo "Backend serves ${backend_commit:-nothing yet}; waiting for $short's backend"
  sleep 20
done
echo "| Backend commit | \`${backend_commit:0:7}\` |" >> "$SUMMARY"

health=$(curl -fsS --max-time 30 "$BACKEND_URL/actuator/health" | jq -r '.status' || true)
[ "$health" = "UP" ] || fail "the backend health check says ${health:-nothing}"
echo "| Backend health | UP (includes the database) |" >> "$SUMMARY"

# 2. Frontend. Vercel records a Production deployment on GitHub for every commit on main.
state=""
while :; do
  deployment=$(gh api "repos/$GITHUB_REPOSITORY/deployments?sha=$SHA&environment=Production" --jq '.[0].id // empty')
  if [ -n "$deployment" ]; then
    state=$(gh api "repos/$GITHUB_REPOSITORY/deployments/$deployment/statuses" --jq '.[0].state // empty')
    [ "$state" = "success" ] && break
    case "$state" in failure | error) fail "Vercel's deployment of $short ended in $state" ;; esac
  fi
  [ "$(date +%s)" -lt "$deadline" ] || fail "Vercel has not deployed $short after $WAIT_MINUTES min (${state:-no deployment})"
  echo "Vercel deployment of $short: ${state:-not started}; waiting"
  sleep 20
done
echo "| Frontend deployment | success |" >> "$SUMMARY"

# The app shell, and a client-side route, which Vercel must rewrite to index.html.
for path in / /login; do
  page=$(curl -fsS --max-time 30 "$FRONTEND_URL$path") || fail "the frontend did not answer $path"
  grep -q 'id="root"' <<< "$page" || fail "the frontend's $path is not the app"
done
echo "| Frontend pages | \`/\` and \`/login\` load the app |" >> "$SUMMARY"
echo "| Result | **Passed** |" >> "$SUMMARY"
echo "Backend and frontend serve $short."
