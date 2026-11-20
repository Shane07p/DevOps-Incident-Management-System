#!/usr/bin/env bash
# Check the demo services and request bounded traffic before the presentation.
# The scheduled run refreshes database/telemetry activity; run again just before the demo.
set -euo pipefail

BACKEND_URL="${BACKEND_URL:-${RENDER_BACKEND_URL:-https://incident-backend-f340.onrender.com}}"
AI_SERVICE_URL="${AI_SERVICE_URL:-http://localhost:8000}"
FRONTEND_URL="${FRONTEND_URL:-${VERCEL_FRONTEND_URL:-https://dev-ops-incident-management-system.vercel.app}}"
DEMO_SHOP_URL="${DEMO_SHOP_URL:-http://localhost:8001}"
# Traffic runs inside the shop, so target its own allowed loopback origin, not its public URL.
DEMO_SHOP_TRAFFIC_TARGET_URL="${DEMO_SHOP_TRAFFIC_TARGET_URL:-http://127.0.0.1:8001}"
CONTROL_TOKEN="${DEMO_SHOP_CONTROL_TOKEN:-}"
WARMUP_TRAFFIC_SECONDS="${WARMUP_TRAFFIC_SECONDS:-30}"
MAX_RETRIES="${MAX_RETRIES:-24}"
RETRY_DELAY="${RETRY_DELAY:-5}"

if ! [[ "$MAX_RETRIES" =~ ^[1-9][0-9]{0,2}$ && "$RETRY_DELAY" =~ ^[0-9]+$ \
  && "$WARMUP_TRAFFIC_SECONDS" =~ ^[1-9][0-9]{0,2}$ ]] \
  || (( WARMUP_TRAFFIC_SECONDS > 300 )); then
  printf '[ERROR] Use positive MAX_RETRIES, nonnegative RETRY_DELAY, and traffic duration 1..300.\n' >&2
  exit 2
fi

http_status() {
  local code curl_exit
  if code=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 15 "$@"); then
    printf '%s' "$code"
  else
    curl_exit=$?
    printf '[WARN] curl transport failure (exit %s); recording HTTP 000.\n' "$curl_exit" >&2
    printf '000'
  fi
}

health_status() {
  local name="$1" url="$2" code=000 attempt
  for ((attempt=1; attempt<=MAX_RETRIES; attempt++)); do
    code=$(http_status "$url")
    if [[ "$code" == 200 ]]; then break; fi
    printf '[WAIT] %s HTTP %s (attempt %s/%s).\n' "$name" "$code" "$attempt" "$MAX_RETRIES" >&2
    if (( attempt < MAX_RETRIES )); then sleep "$RETRY_DELAY"; fi
  done
  printf '%s' "$code"
}

printf 'Settle demo warm-up\n'
printf 'Backend: %s\nAI service: %s\nDemo shop: %s\n' "$BACKEND_URL" "$AI_SERVICE_URL" "$DEMO_SHOP_URL"
backend_status=$(health_status Backend "${BACKEND_URL%/}/actuator/health")
ai_status=$(health_status 'AI service' "${AI_SERVICE_URL%/}/health")
demo_shop_status=$(health_status 'Demo shop' "${DEMO_SHOP_URL%/}/health")
frontend_status=$(http_status "${FRONTEND_URL%/}/")

printf '[CHECK] Backend HTTP %s\n[CHECK] AI service HTTP %s\n[CHECK] Demo shop HTTP %s\n' \
  "$backend_status" "$ai_status" "$demo_shop_status"
printf '[CHECK] Frontend HTTP %s\n' "$frontend_status"
if [[ "$frontend_status" != 200 && "$frontend_status" != 304 ]]; then
  printf '[WARN] Frontend warm-up failed (HTTP %s).\n' "$frontend_status" >&2
fi
if [[ "$backend_status" != 200 || "$ai_status" != 200 || "$demo_shop_status" != 200 ]]; then
  printf '[ERROR] Required service health checks failed; traffic was not started.\n' >&2
  exit 1
fi

auth_header=()
if [[ -n "$CONTROL_TOKEN" ]]; then auth_header=(-H "Authorization: Bearer ${CONTROL_TOKEN}"); fi
target_json=${DEMO_SHOP_TRAFFIC_TARGET_URL//\\/\\\\}
target_json=${target_json//\"/\\\"}
traffic_payload=$(printf '{"duration_seconds":%s,"rate":3.0,"target_url":"%s"}' \
  "$WARMUP_TRAFFIC_SECONDS" "$target_json")
traffic_status=$(http_status "${auth_header[@]}" -H 'Content-Type: application/json' \
  -d "$traffic_payload" "${DEMO_SHOP_URL%/}/traffic/start")
printf '[CHECK] Traffic HTTP %s\n' "$traffic_status"
if [[ "$traffic_status" =~ ^2[0-9]{2}$ ]]; then
  printf '[OK] Grafana Telemetry: FRESH DATA INGESTED (traffic HTTP %s).\n' "$traffic_status"
else
  printf '[ERROR] Traffic HTTP %s; bounded traffic was not accepted.\n' "$traffic_status" >&2
  exit 1
fi
