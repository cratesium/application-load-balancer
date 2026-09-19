#!/usr/bin/env bash
#
# blast.sh — Send ~1000 requests/sec to the ALB and show per-backend distribution.
#
#   ./loadtest/blast.sh
#   DURATION=30 TARGET_RPS=1000 ./loadtest/blast.sh
#
set -euo pipefail

ALB="${ALB:-http://localhost:8080}"
TARGET_PATH="${TARGET_PATH:-/api/test}"
TARGET_RPS="${TARGET_RPS:-1000}"
DURATION="${DURATION:-20}"        # seconds
ADMIN_TOKEN="${ALB_ADMIN_TOKEN:-admin_shikhar}"

URL="${ALB}${TARGET_PATH}"

# ── preflight ────────────────────────────────────────────────────────────────
if ! curl -sf --max-time 5 "${ALB}/actuator/health" > /dev/null; then
  echo "❌  No ALB at ${ALB} — is it running?" >&2
  exit 1
fi

# ── helpers ──────────────────────────────────────────────────────────────────
snapshot() {
  echo "  Requests served (from admin API):"
  curl -s -H "Authorization: Bearer ${ADMIN_TOKEN}" "${ALB}/admin/backends" 2>/dev/null \
    | python3 -c "
import sys, json
bs = json.load(sys.stdin)
for b in bs:
    print(f\"    {b['id']:20s} status={b['status']:4s}  requests={b.get('totalRequests', 'n/a')}\")
" 2>/dev/null || \
  curl -s "${ALB}/actuator/prometheus" \
    | grep '^loadbalancer_backend_active_connections' \
    | sed 's/.*backend="\([^"]*\)".*} \(.*\)/    \1  active=\2/'
}

status_line() {
  curl -s -H "Authorization: Bearer ${ADMIN_TOKEN}" "${ALB}/admin/backends" 2>/dev/null \
    | python3 -c "
import sys, json
d = json.load(sys.stdin)
for b in d:
    print(f\"  {b['id']:20s}  status={b['status']:4s}  active={b.get('activeConnections',0):4d}  total={b.get('totalRequests',0)}\")
" 2>/dev/null || echo "  (admin status unavailable)"
}

# ── choose tool ──────────────────────────────────────────────────────────────
if command -v wrk &>/dev/null; then
  TOOL=wrk
elif command -v k6 &>/dev/null; then
  TOOL=k6
elif command -v ab &>/dev/null; then
  TOOL=ab
else
  TOOL=curl_loop   # pure bash fallback
fi

echo "======================================================================="
echo "  ALB Blast  |  target: ${TARGET_RPS} req/s  |  duration: ${DURATION}s"
echo "  URL        : ${URL}"
echo "  Tool       : ${TOOL}"
echo "======================================================================="
echo
echo "── Backend counters BEFORE ──────────────────────────────────────────────"
snapshot
echo
echo "── Backend status ───────────────────────────────────────────────────────"
status_line
echo

# ── run load ─────────────────────────────────────────────────────────────────
case "$TOOL" in

  wrk)
    # wrk doesn't have a built-in RPS cap; tune threads/connections to approach 1000 rps.
    # With fast local backends 4 threads / 50 connections typically saturates ~1000 rps.
    THREADS=4
    CONNECTIONS=50
    echo "Running: wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION}s --latency ${URL}"
    echo
    wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${DURATION}s" --latency "${URL}"
    ;;

  k6)
    SCRIPT=$(mktemp /tmp/blast_k6_XXXX.js)
    trap "rm -f $SCRIPT" EXIT
    cat > "$SCRIPT" <<JS
import http from 'k6/http';
import { sleep } from 'k6';

export const options = {
  scenarios: {
    constant_rps: {
      executor: 'constant-arrival-rate',
      rate: ${TARGET_RPS},
      timeUnit: '1s',
      duration: '${DURATION}s',
      preAllocatedVUs: 200,
      maxVUs: 500,
    },
  },
};

export default function () {
  http.get('${URL}');
}
JS
    echo "Running k6 at ${TARGET_RPS} req/s for ${DURATION}s …"
    echo
    k6 run "$SCRIPT"
    ;;

  ab)
    TOTAL=$(( TARGET_RPS * DURATION ))
    CONCURRENCY=100
    echo "Running: ab -n${TOTAL} -c${CONCURRENCY} ${URL}"
    echo
    ab -n"${TOTAL}" -c"${CONCURRENCY}" "${URL}/"
    ;;

  curl_loop)
    # Pure-bash fallback: fire requests in background, throttled to ~TARGET_RPS.
    echo "⚠️  No wrk/k6/ab found — using bash curl loop (less accurate)"
    echo "   Install wrk for best results:  brew install wrk"
    echo
    INTERVAL=$(python3 -c "print(1/${TARGET_RPS})")
    END=$(( $(date +%s) + DURATION ))
    SENT=0
    OK=0
    FAIL=0

    while [ "$(date +%s)" -lt "$END" ]; do
      {
        CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "${URL}")
        if [ "$CODE" -ge 200 ] && [ "$CODE" -lt 300 ]; then
          echo "ok"
        else
          echo "fail:${CODE}"
        fi
      } &
      SENT=$(( SENT + 1 ))
      sleep "${INTERVAL}" 2>/dev/null || true
    done
    wait

    echo
    echo "Sent ~${SENT} requests over ${DURATION}s"
    ;;
esac

# ── results ──────────────────────────────────────────────────────────────────
echo
echo "── Backend counters AFTER ───────────────────────────────────────────────"
snapshot
echo
echo "── Backend status ───────────────────────────────────────────────────────"
status_line
echo
echo "── ALB error metrics ────────────────────────────────────────────────────"
curl -s "${ALB}/actuator/prometheus" \
  | grep -E '^loadbalancer_(requests_total|requests_failed|retries_total|circuit_breakers_open|active_requests)' \
  | sed 's/.*} /  /' \
  || echo "  (no error metrics)"
echo
echo "======================================================================="
echo "  Done. BEFORE/AFTER delta per backend shows the actual distribution."
echo "======================================================================="
