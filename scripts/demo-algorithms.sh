#!/usr/bin/env bash
#
# Demonstrates each load balancing algorithm by switching it at runtime and showing the
# resulting distribution. No restarts.
#
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

require_alb

heading "1. ROUND_ROBIN — equal servers, exact rotation"
set_algorithm ROUND_ROBIN
echo "Sequence of 9 requests:"
for i in $(seq 9); do echo "  request $i -> $(which_backend)"; done
echo
echo "Distribution over 30 requests:"
distribution 30

heading "2. WEIGHTED_ROUND_ROBIN — backend-1 given weight 3"
set_algorithm WEIGHTED_ROUND_ROBIN
curl -s "${AUTH[@]}" -X PUT "${ALB}/admin/backends/backend-1/weight" \
  -H 'Content-Type: application/json' -d '{"weight":3}' > /dev/null
echo "Weights are now 3:1:1, so expect roughly 60% / 20% / 20%."
echo
echo "Sequence of 10 requests (note it interleaves rather than sending 3 in a row):"
for i in $(seq 10); do echo "  request $i -> $(which_backend)"; done
echo
echo "Distribution over 50 requests:"
distribution 50
curl -s "${AUTH[@]}" -X PUT "${ALB}/admin/backends/backend-1/weight" \
  -H 'Content-Type: application/json' -d '{"weight":1}' > /dev/null

heading "3. RANDOM — uniform but not exact; converges over many requests"
set_algorithm RANDOM
echo "Distribution over 60 requests (expect roughly even, with visible variance):"
distribution 60

heading "4. LEAST_CONNECTIONS — traffic follows capacity, not a rota"
set_algorithm LEAST_CONNECTIONS
echo "Sending 6 slow requests (2s each) to backend-1 in the background so its"
echo "in-flight count rises, then 20 fast requests to see where they land."
echo
for i in $(seq 6); do
  curl -s --max-time 30 "http://localhost:9001/api/slow?ms=2000" > /dev/null &
done
sleep 0.5
echo "Distribution of 20 fast requests while backend-1 is busy:"
distribution 20
wait

heading "5. WEIGHTED_LEAST_CONNECTIONS — capacity-normalised load"
set_algorithm WEIGHTED_LEAST_CONNECTIONS
curl -s "${AUTH[@]}" -X PUT "${ALB}/admin/backends/backend-1/weight" \
  -H 'Content-Type: application/json' -d '{"weight":5}' > /dev/null
echo "backend-1 has weight 5, the others 1. Score is (active + 1) / weight, lowest wins,"
echo "so backend-1 should attract most traffic while it has headroom."
echo
distribution 40
curl -s "${AUTH[@]}" -X PUT "${ALB}/admin/backends/backend-1/weight" \
  -H 'Content-Type: application/json' -d '{"weight":1}' > /dev/null

heading "6. IP_HASH — session affinity"
set_algorithm IP_HASH
echo "All requests come from this machine, so one backend should serve all of them:"
distribution 20
echo
echo "This is affinity, not balance: a NAT gateway full of users is a single IP."

heading "7. CONSISTENT_HASH — affinity that survives pool changes"
set_algorithm CONSISTENT_HASH
PINNED=$(which_backend)
echo "This client is pinned to: ${PINNED}"
echo
echo "Now disabling a DIFFERENT backend and re-checking. With modulo hashing this client"
echo "would very likely be remapped; on a hash ring it must not move."
for candidate in backend-1 backend-2 backend-3; do
  if [[ "${candidate}" != "${PINNED}" ]]; then
    VICTIM="${candidate}"
    break
  fi
done
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/backends/${VICTIM}/disable" > /dev/null
sleep 1
echo "Disabled ${VICTIM}. This client now reaches: $(which_backend)  (expected ${PINNED})"
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/backends/${VICTIM}/enable" > /dev/null

heading "Restoring ROUND_ROBIN"
set_algorithm ROUND_ROBIN
echo "Done. Current status:"
curl -s "${AUTH[@]}" "${ALB}/admin/status"
echo
