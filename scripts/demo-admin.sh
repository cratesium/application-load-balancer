#!/usr/bin/env bash
#
# Demonstrates the admin API: authentication, inspection, dynamic backend management,
# algorithm switching, weights, config reload and metrics.
#
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

require_alb

heading "1. Admin endpoints are not open"
echo "No token:"
curl -s -i "${ALB}/admin/status" | sed -n '1p'
curl -s "${ALB}/admin/status"; echo
echo
echo "Wrong token:"
curl -s -i -H 'Authorization: Bearer wrong-token' "${ALB}/admin/status" | sed -n '1p'
echo
echo "Correct token:"
curl -s "${AUTH[@]}" "${ALB}/admin/status"; echo

heading "2. Proxied traffic needs no token"
curl -s -o /dev/null -w 'GET /api/test -> %{http_code}\n' "${ALB}/api/test"

heading "3. GET /admin/backends"
curl -s "${AUTH[@]}" "${ALB}/admin/backends"; echo

heading "4. GET /admin/algorithm"
curl -s "${AUTH[@]}" "${ALB}/admin/algorithm"; echo

heading "5. Switching the algorithm at runtime"
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/load-balancer/algorithm" \
  -H 'Content-Type: application/json' -d '{"algorithm":"LEAST_CONNECTIONS"}'
echo
echo "An invalid algorithm is rejected with the valid values listed:"
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/load-balancer/algorithm" \
  -H 'Content-Type: application/json' -d '{"algorithm":"NOT_AN_ALGORITHM"}'
echo
set_algorithm ROUND_ROBIN

heading "6. Registering a backend at runtime"
echo "Adding backend-3 a second time under a new id, pointing at the same process."
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/backends" \
  -H 'Content-Type: application/json' \
  -d '{"id":"backend-4","host":"backend-3","port":8080,"weight":1}'
echo
echo
echo "Distribution over 20 requests — four backends now share the traffic:"
distribution 20

heading "7. Duplicate ids are refused, not silently overwritten"
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/backends" \
  -H 'Content-Type: application/json' \
  -d '{"id":"backend-1","host":"evil.example.com","port":80}'
echo

heading "8. Changing a weight at runtime"
set_algorithm WEIGHTED_ROUND_ROBIN
curl -s "${AUTH[@]}" -X PUT "${ALB}/admin/backends/backend-1/weight" \
  -H 'Content-Type: application/json' -d '{"weight":4}'
echo
echo
echo "Distribution over 35 requests with weights 4:1:1:1:"
distribution 35
curl -s "${AUTH[@]}" -X PUT "${ALB}/admin/backends/backend-1/weight" \
  -H 'Content-Type: application/json' -d '{"weight":1}' > /dev/null
set_algorithm ROUND_ROBIN

heading "9. Disable and enable"
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/backends/backend-2/disable"; echo
sleep 1
echo "Distribution with backend-2 disabled:"
distribution 12
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/backends/backend-2/enable"; echo
sleep 8
echo "Distribution after re-enabling (it must pass health checks first):"
distribution 12

heading "10. Removing a backend"
curl -s "${AUTH[@]}" -X DELETE "${ALB}/admin/backends/backend-4"; echo
sleep 2
curl -s "${AUTH[@]}" "${ALB}/admin/backends" | grep -o '"id":"[^"]*"'

heading "11. Configuration reload"
curl -s "${AUTH[@]}" -X POST "${ALB}/admin/config/reload"; echo

heading "12. Routes"
curl -s "${AUTH[@]}" "${ALB}/admin/routes"; echo

heading "13. Metrics"
echo "Prometheus endpoint (load balancer metrics only):"
curl -s "${ALB}/actuator/prometheus" | grep -E '^loadbalancer_' | head -25
echo
echo "Actuator does not expose env/configprops — they would leak the admin token:"
curl -s -o /dev/null -w '  /actuator/env         -> %{http_code}\n' "${ALB}/actuator/env"
curl -s -o /dev/null -w '  /actuator/configprops -> %{http_code}\n' "${ALB}/actuator/configprops"
curl -s -o /dev/null -w '  /actuator/health      -> %{http_code}\n' "${ALB}/actuator/health"
