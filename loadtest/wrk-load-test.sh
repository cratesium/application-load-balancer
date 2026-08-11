#!/usr/bin/env bash
#
# wrk load test with before/after metric snapshots.
#
#   ./loadtest/wrk-load-test.sh
#   THREADS=8 CONNECTIONS=400 DURATION=60s ./loadtest/wrk-load-test.sh
#
# wrk is the right tool when the question is "what is the ceiling": it generates far more load
# per client core than a thread-per-connection tool, so the harness is unlikely to be the
# bottleneck. Its weakness is that it cannot assert on response bodies, so it tells you the
# throughput but not which backend served what — hence the metric snapshots below, which
# recover the distribution from the ALB itself.
#
set -euo pipefail

ALB="${ALB:-http://localhost:8080}"
THREADS="${THREADS:-4}"
CONNECTIONS="${CONNECTIONS:-100}"
DURATION="${DURATION:-30s}"
PATH_UNDER_TEST="${PATH_UNDER_TEST:-/api/test}"

if ! command -v wrk > /dev/null 2>&1; then
  echo "wrk is not installed." >&2
  echo "  macOS:  brew install wrk" >&2
  echo "  Debian: apt-get install wrk" >&2
  echo "Or use the k6 script instead: k6 run loadtest/k6-load-test.js" >&2
  exit 1
fi

if ! curl -sf --max-time 5 "${ALB}/actuator/health" > /dev/null; then
  echo "No load balancer responding at ${ALB}" >&2
  exit 1
fi

snapshot() {
  curl -s "${ALB}/actuator/prometheus" \
    | grep -E '^loadbalancer_backend_requests_total' \
    | sed 's/^loadbalancer_backend_requests_total//'
}

echo "======================================================================"
echo " wrk: ${THREADS} threads, ${CONNECTIONS} connections, ${DURATION}"
echo " target: ${ALB}${PATH_UNDER_TEST}"
echo "======================================================================"
echo
echo "--- Backend counters BEFORE ---"
snapshot
echo

# --latency prints the full distribution, which is the point: requests/sec alone says nothing
# about whether the slowest 1% of users had an acceptable experience.
wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${DURATION}" --latency "${ALB}${PATH_UNDER_TEST}"

echo
echo "--- Backend counters AFTER ---"
snapshot
echo
echo "--- Load balancer state ---"
curl -s "${ALB}/actuator/prometheus" | grep -E '^loadbalancer_(active_requests|concurrency|circuit_breakers_open|retries_total|requests_failed_total)' || true
echo
echo "======================================================================"
echo " How to read this"
echo "======================================================================"
cat <<'NOTES'

Requests/sec        Throughput. Compare against the same test pointed straight at one
                    backend: the gap is the ALB's cost. If the ALB number is far lower,
                    check for connection-pool starvation (pending-acquire timeouts) before
                    blaming CPU.

Latency avg/max     A max far above p99 usually means a GC pause or a pool acquisition
                    wait, not slow request handling.

Latency p50/p99     The two numbers worth alerting on. A proxy adding <1ms at p50 but 50ms
                    at p99 is queueing somewhere — most often max-connections set too low
                    for the offered concurrency.

Socket errors       Connect/read errors here mean the ALB or the OS refused connections.
                    Check limits.max-concurrent-requests, file descriptor limits (ulimit -n)
                    and the ephemeral port range.

Non-2xx responses   503s point at max-concurrent-requests, an empty backend pool or an open
                    circuit. 504s point at slow backends. The distinction matters: the
                    former is ALB capacity, the latter is upstream.

Backend counters    The BEFORE/AFTER deltas per backend show the real distribution. An even
                    split confirms the algorithm; a skew means a weighted algorithm, a DOWN
                    backend, or an open breaker.

Also worth watching while the test runs:

  watch -n1 'curl -s -H "Authorization: Bearer $ALB_ADMIN_TOKEN" \
      http://localhost:8080/admin/status'

  # per-backend in-flight counts, which is what least-connections routes on
  curl -s http://localhost:8080/actuator/prometheus \
    | grep loadbalancer_backend_active_connections
NOTES
