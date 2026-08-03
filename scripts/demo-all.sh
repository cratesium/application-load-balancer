#!/usr/bin/env bash
#
# Runs every demo in order. Takes a few minutes, mostly because the health-check demos have
# to wait for real probe intervals to elapse.
#
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

require_alb

echo
echo "######################################################################"
echo "#  Java Application Load Balancer — full demonstration"
echo "#"
echo "#  ALB:      ${ALB}"
echo "#  Backends: http://localhost:9001, :9002, :9003 (direct access)"
echo "######################################################################"

./demo-proxy.sh
./demo-algorithms.sh
./demo-admin.sh
./demo-resilience.sh

heading "All demonstrations complete"
curl -s "${AUTH[@]}" "${ALB}/admin/status"
echo
