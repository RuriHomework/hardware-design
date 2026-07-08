#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

report="${1:-build/vivado/BoardTop_routed_timing.rpt}"
if [ ! -f "$report" ]; then
  echo "Missing report: $report" >&2
  exit 1
fi

echo "Report: $report"
rg -n \
  'Setup :|Slack \(VIOLATED\)|Slack \(MET\)|Source:|Destination:|Data Path Delay:|Logic Levels:|Requirement:' \
  "$report" | head -n 80
