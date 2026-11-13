#!/usr/bin/env bash
# Reconnect storm (S4) under four combinations of client and server behaviour, to see what each layer
# of defence is worth. 4000 connected users, the backend is restarted, and a REST probe runs alongside.
#
#   V0 legacy client, gate off      V1 spread client, gate off
#   V2 legacy client, gate on       V3 spread client, gate on
#
#   BENCH_OUT=/some/dir tests/load/run-storm.sh     # needs accounts from seed.mjs (>= 4001)
set -uo pipefail
cd "$(dirname "$0")/../.."
: "${BENCH_OUT:?set BENCH_OUT}"
R="$BENCH_OUT/results-storm"; mkdir -p "$R"
LOG="$BENCH_OUT/progress-storm.log"
say() { echo "$(date +%H:%M:%S) $*" | tee -a "$LOG"; }
caffeinate -dimsu -w $$ &

run() { # name gate client
  local name="$1" gate="$2" client="$3"
  [ -s "$R/$name.json" ] && { say "skip $name"; return; }
  say "run $name (gate $gate, client $client)"
  GATE=$gate tests/load/backend.sh start sse >> "$LOG" 2>&1
  GATE=$gate node tests/load/bench.mjs reconnect --mode sse --users 4000 --warmup 30 --client "$client" \
    --out "$R/$name.json" > "$R/$name.log" 2>&1 || say "FAILED $name"
  sleep 20
}

run V0_legacy_gateoff off legacy
run V1_spread_gateoff off spread
run V2_legacy_gateon on legacy
run V3_spread_gateon on spread
tests/load/backend.sh stop >> "$LOG" 2>&1
say "== done"
