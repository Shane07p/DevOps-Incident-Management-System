#!/usr/bin/env bash
# Exercise the actual warm-up script with a deterministic curl stub; no deployed services are contacted.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
warmup_script="${1:-${repo_root}/scripts/demo-warmup.sh}"
test_dir=$(mktemp -d)
cleanup() {
  rm -f -- "$test_dir/curl" "$test_dir/output" "$test_dir/requests" "$test_dir/retry"
  rmdir -- "$test_dir"
}
trap cleanup EXIT

cat > "$test_dir/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
url="${!#}"
printf '%s\n' "$url" >> "$WARMUP_TEST_REQUESTS"
case "$url" in
  */actuator/health)
    code="$BACKEND_HTTP_CODE"
    if [[ "$code" == recover ]]; then
      if [[ -e "$WARMUP_TEST_RETRY" ]]; then code=200
      else touch "$WARMUP_TEST_RETRY"; code=503; fi
    fi
    ;;
  http://ai.test/health) code="$AI_HTTP_CODE" ;;
  */health) code="$SHOP_HTTP_CODE" ;;
  */traffic/start)
    code="$TRAFFIC_HTTP_CODE"
    while (( $# > 0 )); do
      if [[ "$1" == -d ]]; then printf '%s\n' "$2" >> "$WARMUP_TEST_REQUESTS"; break; fi
      shift
    done
    ;;
  *) code=200 ;;
esac
if [[ "$code" == transport ]]; then printf '000'; exit 7; fi
printf '%s' "$code"
CURL
chmod +x "$test_dir/curl"

run_case() {
  local name="$1" expected="$2" actual
  shift 2
  : > "$test_dir/requests"
  rm -f -- "$test_dir/retry"
  if env PATH="$test_dir:$PATH" \
    WARMUP_TEST_REQUESTS="$test_dir/requests" WARMUP_TEST_RETRY="$test_dir/retry" \
    BACKEND_URL=http://backend.test AI_SERVICE_URL=http://ai.test \
    FRONTEND_URL=http://frontend.test DEMO_SHOP_URL=http://127.0.0.1:8001 \
    DEMO_SHOP_CONTROL_TOKEN=synthetic-test-token DEMO_SHOP_TRAFFIC_TARGET_URL=http://127.0.0.1:8001 \
    MAX_RETRIES=2 RETRY_DELAY=0 WARMUP_TRAFFIC_SECONDS=1 \
    BACKEND_HTTP_CODE=200 AI_HTTP_CODE=200 SHOP_HTTP_CODE=200 TRAFFIC_HTTP_CODE=200 \
    "$@" bash "$warmup_script" > "$test_dir/output" 2>&1; then actual=0
  else actual=$?; fi

  if [[ "$actual" != "$expected" ]]; then
    cat "$test_dir/output"
    printf 'FAIL %s: expected exit %s, got %s\n' "$name" "$expected" "$actual" >&2
    exit 1
  fi
  if [[ "$actual" != 0 ]] && grep -q 'FRESH DATA INGESTED' "$test_dir/output"; then
    cat "$test_dir/output"
    printf 'FAIL %s: failed checks claimed fresh telemetry\n' "$name" >&2
    exit 1
  fi
  if [[ "$name" == *-503 ]] && grep -q '/traffic/start' "$test_dir/requests"; then
    printf 'FAIL %s: traffic ran with an unhealthy required service\n' "$name" >&2
    exit 1
  fi
  printf 'PASS %s: exit %s\n' "$name" "$actual"
  if [[ "$name" == all-healthy || "$name" == traffic-401 ]]; then
    printf '%s\n' "--- $name script output ---"
    cat "$test_dir/output"
  fi
}

run_case all-healthy 0
grep -q 'FRESH DATA INGESTED' "$test_dir/output"
run_case backend-503 1 BACKEND_HTTP_CODE=503
run_case ai-service-503 1 AI_HTTP_CODE=503
run_case demo-shop-503 1 SHOP_HTTP_CODE=503
run_case traffic-401 1 TRAFFIC_HTTP_CODE=401
run_case traffic-422 1 TRAFFIC_HTTP_CODE=422
run_case connection-failed 1 BACKEND_HTTP_CODE=transport
run_case retry-recovers 0 BACKEND_HTTP_CODE=recover
[[ $(grep -c '/actuator/health' "$test_dir/requests") == 2 ]]
run_case traffic-202 0 TRAFFIC_HTTP_CODE=202
run_case public-shop-internal-target 0 DEMO_SHOP_URL=https://shop.example
grep -Eq '"target_url"[[:space:]]*:[[:space:]]*"http://127\.0\.0\.1:8001"' "$test_dir/requests"
run_case custom-internal-port 0 DEMO_SHOP_TRAFFIC_TARGET_URL=http://localhost:10000
grep -Eq '"target_url"[[:space:]]*:[[:space:]]*"http://localhost:10000"' "$test_dir/requests"
printf 'All 11 warm-up checks passed.\n'
