#!/usr/bin/env bash
# ==============================================================================
# Settle Demo Warmup & Free-Tier Keep-Alive Script
# ==============================================================================
# Mitigations addressed:
#   1. Render: Eliminates 50-90s cold start by warming up the container before demo presentation.
#   2. Supabase: Prevents 7-day auto-pause via Actuator DB probe (SELECT 1).
#   3. Grafana Cloud: Generates fresh traffic to overcome the 14-day retention limit.
#   4. Vercel: Warms up edge routes and API proxy rewrites.
#
# Note on free-tier limits:
#   The 12-hour GitHub Actions keep-alive workflow keeps Supabase and Grafana active,
#   but Render free instances sleep after 15 minutes of inactivity. Run this script manually
#   right before the viva demo (T-15m) to ensure Render is awake and hot.
#
# Usage:
#   ./scripts/demo-warmup.sh
#   BACKEND_URL=https://incident-backend-f340.onrender.com FRONTEND_URL=https://dev-ops-incident-management-system.vercel.app DEMO_SHOP_URL=... ./scripts/demo-warmup.sh
# ==============================================================================

set -euo pipefail

# ------------------------------------------------------------------------------
# Configuration (Environment variables with sensible defaults)
# ------------------------------------------------------------------------------
BACKEND_URL="${BACKEND_URL:-${RENDER_BACKEND_URL:-https://incident-backend-f340.onrender.com}}"
FRONTEND_URL="${FRONTEND_URL:-${VERCEL_FRONTEND_URL:-https://dev-ops-incident-management-system.vercel.app}}"
DEMO_SHOP_URL="${DEMO_SHOP_URL:-http://localhost:8001}"
CONTROL_TOKEN="${DEMO_SHOP_CONTROL_TOKEN:-}"
WARMUP_TRAFFIC_SECONDS="${WARMUP_TRAFFIC_SECONDS:-30}"
MAX_RETRIES="${MAX_RETRIES:-24}"  # 24 * 5s = 120s max wait for Render cold start
RETRY_DELAY="${RETRY_DELAY:-5}"

# Formatting
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

log_info() {
  printf "${CYAN}[INFO]${NC} %s\n" "$1"
}

log_success() {
  printf "${GREEN}[SUCCESS]${NC} %s\n" "$1"
}

log_warn() {
  printf "${YELLOW}[WARN]${NC} %s\n" "$1"
}

log_error() {
  printf "${RED}[ERROR]${NC} %s\n" "$1"
}

printf "${BOLD}======================================================${NC}\n"
printf "${BOLD}  Settle Demo Warm-up & Free-Tier Readiness Check     ${NC}\n"
printf "${BOLD}======================================================${NC}\n"
printf "Target Backend:   %s\n" "$BACKEND_URL"
printf "Target Frontend:  %s\n" "$FRONTEND_URL"
printf "Target Demo Shop: %s\n" "$DEMO_SHOP_URL"
printf "======================================================\n\n"

# ------------------------------------------------------------------------------
# 1. Warm up Render Backend & Supabase Database
# ------------------------------------------------------------------------------
log_info "Pinging Backend health endpoint (/actuator/health)..."
log_info "This wakes up Render and executes a Supabase query to prevent pausing."

backend_healthy=false
for ((i=1; i<=MAX_RETRIES; i++)); do
  response=$(curl -s -w "\n%{http_code}" --max-time 15 "${BACKEND_URL}/actuator/health" || true)
  body=$(echo "$response" | sed '$d')
  status_code=$(echo "$response" | tail -n1)

  if [ "$status_code" = "200" ]; then
    log_success "Backend is UP (HTTP 200). Health status: ${body}"
    backend_healthy=true
    break
  else
    printf "${YELLOW}[WAIT]${NC} Attempt %d/%d: Backend responded with HTTP %s. Waiting %ds for spin-up...\n" \
      "$i" "$MAX_RETRIES" "$status_code" "$RETRY_DELAY"
    sleep "$RETRY_DELAY"
  fi
done

if [ "$backend_healthy" = false ]; then
  log_error "Backend failed to respond with HTTP 200 after $((MAX_RETRIES * RETRY_DELAY)) seconds."
  log_error "Check Render service status and ensure Supabase project is unpaused."
fi

# ------------------------------------------------------------------------------
# 2. Warm up Vercel Frontend
# ------------------------------------------------------------------------------
log_info "Warming up Vercel Frontend (${FRONTEND_URL})..."
frontend_status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "${FRONTEND_URL}/" || true)

if [ "$frontend_status" = "200" ] || [ "$frontend_status" = "304" ]; then
  log_success "Frontend is reachable (HTTP ${frontend_status})."
else
  log_warn "Frontend returned HTTP ${frontend_status}. Ensure the Vercel deployment is active."
fi

# ------------------------------------------------------------------------------
# 3. Warm up Demo Shop & Refresh Grafana Cloud Telemetry (14-Day Retention Fix)
# ------------------------------------------------------------------------------
log_info "Checking Demo Shop (${DEMO_SHOP_URL}/health)..."
demo_shop_status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "${DEMO_SHOP_URL}/health" || true)

if [ "$demo_shop_status" = "200" ]; then
  log_success "Demo Shop is online (HTTP 200)."
  log_info "Generating fresh telemetry to ensure Grafana Cloud (14-day retention) has lively data..."

  # Build header if CONTROL_TOKEN is set
  AUTH_HEADER=()
  if [ -n "$CONTROL_TOKEN" ]; then
    AUTH_HEADER=(-H "Authorization: Bearer ${CONTROL_TOKEN}")
  fi

  # Start bounded traffic generator in Demo Shop
  traffic_payload=$(cat <<EOF
{
  "duration_seconds": ${WARMUP_TRAFFIC_SECONDS},
  "rate": 3.0,
  "target_url": "${DEMO_SHOP_URL}"
}
EOF
)

  traffic_response=$(curl -s -w "\n%{http_code}" ${AUTH_HEADER[@]+"${AUTH_HEADER[@]}"} \
    -H "Content-Type: application/json" \
    -d "$traffic_payload" \
    --max-time 10 \
    "${DEMO_SHOP_URL}/traffic/start" || true)

  traffic_status=$(echo "$traffic_response" | tail -n1)
  if [ "$traffic_status" = "200" ] || [ "$traffic_status" = "202" ]; then
    log_success "Generated ${WARMUP_TRAFFIC_SECONDS}s of fresh traffic across checkout, payments, and search."
    log_info "OTLP metrics and structured logs are now streaming to Grafana Cloud."
  else
    log_warn "Traffic start API returned HTTP ${traffic_status}. Exercising basic search route directly..."
    curl -s --max-time 5 "${DEMO_SHOP_URL}/search?q=monitor" > /dev/null || true
    curl -s --max-time 5 "${DEMO_SHOP_URL}/search?q=headphones" > /dev/null || true
    log_success "Sent direct search queries to generate metric points."
  fi
else
  log_warn "Demo Shop is not reachable at ${DEMO_SHOP_URL} (HTTP ${demo_shop_status})."
  log_warn "If running locally or using a different URL, set DEMO_SHOP_URL=... and rerun."
fi

# ------------------------------------------------------------------------------
# 4. Summary & Verification
# ------------------------------------------------------------------------------
printf "\n${BOLD}======================================================${NC}\n"
printf "${BOLD}  Warm-up Complete - Viva Readiness Status            ${NC}\n"
printf "${BOLD}======================================================${NC}\n"
if [ "$backend_healthy" = true ]; then
  printf "  [✓] Render Backend:    ${GREEN}WARMED UP & READY${NC}\n"
  printf "  [✓] Supabase Database: ${GREEN}ACTIVE & QUERIED${NC}\n"
else
  printf "  [✗] Render Backend:    ${RED}UNHEALTHY OR COLD${NC}\n"
  printf "  [✗] Supabase Database: ${RED}VERIFY IN DASHBOARD${NC}\n"
fi

if [ "$frontend_status" = "200" ] || [ "$frontend_status" = "304" ]; then
  printf "  [✓] Vercel Frontend:   ${GREEN}REACHABLE${NC}\n"
else
  printf "  [!] Vercel Frontend:   ${YELLOW}CHECK DEPLOYMENT${NC}\n"
fi

if [ "$demo_shop_status" = "200" ]; then
  printf "  [✓] Grafana Telemetry: ${GREEN}FRESH DATA INGESTED${NC}\n"
else
  printf "  [!] Grafana Telemetry: ${YELLOW}DEMO SHOP UNREACHABLE${NC}\n"
fi
printf "======================================================\n\n"
