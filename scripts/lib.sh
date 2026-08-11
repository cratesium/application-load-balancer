#!/usr/bin/env bash
#
# Shared helpers for the demo scripts.
#
# Deliberately dependency-free: no jq, no python. The demos need to run on a fresh
# machine, and "install jq first" is a bad first step for a demo.

ALB="${ALB:-http://localhost:8080}"
ADMIN_TOKEN="${ALB_ADMIN_TOKEN:-demo-token-please-change-me-32chars}"
AUTH=(-H "Authorization: Bearer ${ADMIN_TOKEN}")

# Extracts one string field from a flat JSON object without a JSON parser.
# Adequate for the demo backends' small, flat responses.
json_field() {
  local field="$1"
  sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}

# Prints which backend served a single request to $1 (default /api/test).
which_backend() {
  local path="${1:-/api/test}"
  curl -s --max-time 10 "${ALB}${path}" | json_field server
}

# Sends $1 requests and prints a sorted count per backend.
distribution() {
  local count="${1:-30}"
  local path="${2:-/api/test}"
  local i
  for ((i = 0; i < count; i++)); do
    which_backend "$path"
  done | sort | uniq -c | sort -rn
}

# Switches the active algorithm through the admin API.
set_algorithm() {
  curl -s "${AUTH[@]}" -X POST "${ALB}/admin/load-balancer/algorithm" \
    -H 'Content-Type: application/json' \
    -d "{\"algorithm\":\"$1\"}"
  echo
}

heading() {
  echo
  echo "=============================================================="
  echo "  $*"
  echo "=============================================================="
}

require_alb() {
  if ! curl -sf --max-time 5 "${ALB}/actuator/health" > /dev/null; then
    echo "ERROR: no load balancer responding at ${ALB}" >&2
    echo "Start the stack first:  docker compose up --build" >&2
    exit 1
  fi
}
