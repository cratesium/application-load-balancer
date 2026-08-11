#!/usr/bin/env bash
#
# Demonstrates health checks, failure detection, retries, the circuit breaker, connection
# draining and the no-healthy-backend response.
#
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

require_alb
set_algorithm ROUND_ROBIN > /dev/null

backend_states() {
  curl -s "${AUTH[@]}" "${ALB}/admin/backends" \
    | tr '{' '\n' \
    | sed -n 's/.*"id":"\([^"]*\)".*"status":"\([^"]*\)".*/  \1 -> \2/p'
}

heading "1. Baseline — all backends UP"
backend_states

heading "2. Active health checks: failure threshold before removal"
echo "Making backend-1 report unhealthy at the source."
curl -s -X POST http://localhost:9001/admin/health/down > /dev/null
echo "failure-threshold is 3 and the interval is 5s, so it takes up to ~15s to be removed."
echo "Two failed probes are not enough — that is what stops a blip from removing a server."
for i in $(seq 5); do
  sleep 4
  echo "after ~$((i * 4))s:"
  backend_states
done

heading "3. Unhealthy backends receive no traffic"
echo "Distribution over 20 requests (backend-1 should be absent):"
distribution 20

heading "4. Recovery requires success-threshold consecutive successes"
curl -s -X POST http://localhost:9001/admin/health/up > /dev/null
echo "Backend-1 is healthy again; it needs 2 consecutive good probes to return."
for i in $(seq 4); do
  sleep 4
  echo "after ~$((i * 4))s:"
  backend_states
done

heading "5. Retry — a dead backend does not fail client requests"
echo "Registering a backend on a port with nothing listening, weighted heavily so it is"
echo "chosen often. Every client request should still return 200 thanks to retries."
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/backends" \
  -H 'Content-Type: application/json' \
  -d '{"id":"dead-backend","host":"127.0.0.1","port":9999,"weight":5}' > /dev/null
echo
echo "20 requests, showing HTTP status only:"
for i in $(seq 20); do
  printf '%s ' "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "${ALB}/api/test")"
done
echo
echo
echo "Retry and failure counters:"
curl -s "${ALB}/actuator/prometheus" | grep -E '^loadbalancer_(retries_total|backend_failures_total)' || true

heading "6. Circuit breaker — the ALB stops trying a backend that keeps failing"
echo "Driving more traffic so the dead backend crosses the failure-rate threshold."
for i in $(seq 30); do curl -s -o /dev/null --max-time 10 "${ALB}/api/test"; done
echo
echo "Dead backend state (note circuitState and lastFailureReason):"
curl -s "${AUTH[@]}" "${ALB}/admin/backends/dead-backend"
echo
echo
echo "Circuit breaker metrics:"
curl -s "${ALB}/actuator/prometheus" | grep -E '^loadbalancer_circuit' || true

heading "7. Connection draining on removal"
echo "DELETE returns 202 and drains in-flight requests before removing the backend."
curl -s "${AUTH[@]}" -X DELETE "${ALB}/admin/backends/dead-backend"
echo
sleep 2
echo "Backends now:"
backend_states

heading "8. No healthy backend -> 503 with a machine-readable body"
for port in 9001 9002 9003; do
  curl -s -X POST "http://localhost:${port}/admin/health/down" > /dev/null
done
echo "All three backends now report unhealthy; waiting for the ALB to notice..."
sleep 18
echo
curl -s -i --max-time 10 "${ALB}/api/test" | sed -n '1p;/^$/,$p'
echo

heading "9. Recovery"
for port in 9001 9002 9003; do
  curl -s -X POST "http://localhost:${port}/admin/health/up" > /dev/null
done
sleep 14
backend_states
echo
echo "Distribution is back to normal:"
distribution 12
