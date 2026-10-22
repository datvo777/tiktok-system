#!/usr/bin/env bash
# Runs the whole SSE-vs-polling matrix (docs/sse-vs-polling-benchmark.md) and leaves one JSON per run
# in $BENCH_OUT/results. Takes about 90 minutes. Needs: compose stack up, `mvn package -DskipTests`,
# the scratch database, and $BENCH_OUT/accounts.json from seed.mjs.
#
#   BENCH_OUT=/some/dir tests/load/run-all.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
: "${BENCH_OUT:?set BENCH_OUT}"
R="$BENCH_OUT/results"; mkdir -p "$R"
LOG="$BENCH_OUT/progress.log"
WARMUP=30; WINDOW=90; REPS=3
say() { echo "$(date +%H:%M:%S) $*" | tee -a "$LOG"; }
# A run whose JSON already exists is skipped, so an interrupted matrix can be resumed.
bench() { local out="$1"; shift; [ -s "$R/$out.json" ] && { say "skip $out (done)"; return; }; say "run $out"; node tests/load/bench.mjs "$@" --out "$R/$out.json" > "$R/$out.log" 2>&1 || say "FAILED $out"; sleep 25; }

for MODE in polling sse; do
  say "== session $MODE"
  for N in 1000 4000 8000; do
    tests/load/backend.sh start $MODE >> "$LOG" 2>&1
    for r in $(seq 1 $REPS); do
      bench "steady_${MODE}_N${N}_w0_r$r" steady --mode $MODE --users $N --waiting 0 --warmup $WARMUP --window $WINDOW
    done
  done
  tests/load/backend.sh start $MODE >> "$LOG" 2>&1
  for r in $(seq 1 $REPS); do
    bench "steady_${MODE}_N4000_w10_r$r" steady --mode $MODE --users 4000 --waiting 0.1 --warmup $WARMUP --window $WINDOW
  done

  tests/load/backend.sh start $MODE >> "$LOG" 2>&1
  bench "latency_${MODE}" latency --mode $MODE --users 2000 --events 200 --warmup 20
  bench "burst_${MODE}_100" burst --mode $MODE --users 2000 --events 100 --warmup 20
  bench "burst_${MODE}_1000" burst --mode $MODE --users 2000 --events 1000 --warmup 20
  # Mass reconnect: restarts the backend itself. The 0-user run is the control for JVM start-up cost.
  bench "reconnect_${MODE}_N0" reconnect --mode $MODE --users 0 --warmup 5
  bench "reconnect_${MODE}_N4000" reconnect --mode $MODE --users 4000 --warmup 30
done

tests/load/backend.sh start sse >> "$LOG" 2>&1
bench "leak" leak --cycles 100000 --concurrency 200
say "== done"
