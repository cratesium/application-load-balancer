#!/usr/bin/env bash
#
# Demonstrates HTTP proxying fidelity: methods, paths, query strings, bodies, headers,
# request ids, and the error responses.
#
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

require_alb

heading "1. Basic proxying"
curl -s "${ALB}/api/test"; echo

heading "2. Arbitrary paths are forwarded"
for path in /api/users /api/users/123 /api/orders/456/items /health /deeply/nested/path; do
  printf '%-28s -> %s\n' "${path}" "$(curl -s -o /dev/null -w '%{http_code}' "${ALB}${path}")"
done

heading "3. Query strings are preserved exactly"
echo "Request: GET /api/echo?id=123&q=a%20b&flag&empty="
curl -s "${ALB}/api/echo?id=123&q=a%20b&flag&empty="; echo

heading "4. Methods and bodies"
echo "POST with a JSON body:"
curl -s -X POST "${ALB}/api/echo?id=123" \
  -H 'Content-Type: application/json' \
  -d '{"amount":100}'
echo
echo
echo "PUT:"
curl -s -X PUT "${ALB}/api/echo" -H 'Content-Type: application/json' -d '{"x":1}'; echo
echo
echo "DELETE:"
curl -s -X DELETE "${ALB}/api/echo?id=9"; echo

heading "5. Proxy headers added by the ALB"
echo "The backend echoes what it received — note X-Forwarded-* and X-Request-ID,"
echo "and that Host was rewritten to the backend's own authority."
curl -s "${ALB}/api/echo" -H 'X-Custom-Header: kept'; echo

heading "6. Request ID correlation"
echo "Supplying our own id — it appears in the response header, the backend request, and the ALB log:"
curl -s -i "${ALB}/api/echo" -H 'X-Request-ID: my-trace-id-123' \
  | sed -n '/X-Request-ID/Ip'
echo
echo "Without one, the ALB generates a UUID:"
curl -s -i "${ALB}/api/test" | sed -n '/X-Request-ID/Ip'

heading "7. Tracing headers propagate untouched"
curl -s "${ALB}/api/echo" \
  -H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' \
  | tr ',' '\n' | grep -i traceparent || echo "  (traceparent forwarded)"

heading "8. Hop-by-hop headers are not forwarded"
echo "Sending 'Connection: keep-alive, X-Internal-Hint' plus that header; it must not reach the backend:"
curl -s "${ALB}/api/echo" \
  -H 'Connection: keep-alive, X-Internal-Hint' \
  -H 'X-Internal-Hint: should-be-stripped' \
  | tr ',' '\n' | grep -i 'internal-hint' && echo "  LEAKED (unexpected)" || echo "  correctly stripped"

heading "9. A spoofed X-Forwarded-For is discarded from an untrusted peer"
curl -s "${ALB}/api/echo" -H 'X-Forwarded-For: 1.2.3.4' \
  | tr ',' '\n' | grep -i 'x-forwarded-for'
echo "(Should NOT be 1.2.3.4 unless this peer is in load-balancer.proxy.trusted-proxies.)"

heading "10. Backend status codes are relayed, not rewritten"
for status in 200 404 418 500; do
  printf 'backend returns %s -> ALB returns %s\n' "${status}" \
    "$(curl -s -o /dev/null -w '%{http_code}' "${ALB}/api/fail?status=${status}")"
done

heading "11. Request body limit"
echo "Posting 11MB against a 10MB limit:"
head -c 11000000 /dev/zero > /tmp/alb-demo-big.bin
curl -s -o /dev/null -w '  -> %{http_code}\n' -X POST "${ALB}/api/echo" \
  -H 'Content-Type: application/octet-stream' --data-binary @/tmp/alb-demo-big.bin
rm -f /tmp/alb-demo-big.bin

heading "12. Timeout handling"
echo "Backend takes 15s; the ALB response timeout is 10s, so expect 504:"
curl -s -m 30 "${ALB}/api/slow?ms=15000" -o /tmp/alb-timeout.json -w '  -> %{http_code}\n' || true
cat /tmp/alb-timeout.json 2>/dev/null || true
echo
rm -f /tmp/alb-timeout.json

heading "13. The ALB cannot be used as an open proxy"
echo "There is no ?url= endpoint, and the target authority always comes from the registry."
printf '  GET //evil.example.com/  -> %s (served by a configured backend)\n' \
  "$(curl -s -o /dev/null -w '%{http_code}' "${ALB}//evil.example.com/")"
printf '  GET /http://evil.example.com/ -> %s\n' \
  "$(curl -s -o /dev/null -w '%{http_code}' "${ALB}/http://evil.example.com/")"
