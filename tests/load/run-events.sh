#!/usr/bin/env bash
# The event scenarios (S2 latency, S3 bursts) and the leak check, for both modes. Split from run-all.sh
# after the first matrix reused follower/target pairs across runs: a repeated follow is a no-op that
# raises no event, so those runs measured nothing. Every run here gets its own followers via --offset.
#
#   BENCH_OUT=/some/dir tests/load/run-events.sh      # needs fresh accounts from seed.mjs
set -uo pipefail
cd "$(dirname "$0")/../.."
: "${BENCH_OUT:?set BENCH_OUT}"
R="$BENCH_OUT/results"; mkdir -p "$R"
LOG="$BENCH_OUT/progress-events.log"
say() { echo "$(date +%H:%M:%S) $*" | tee -a "$LOG"; }
bench() { local out="$1"; shift; [ -s "$R/$out.json" ] && { say "skip $out (done)"; return; }; say "run $out"; node tests/load/bench.mjs "$@" --out "$R/$out.json" > "$R/$out.log" 2>&1 || say "FAILED $out"; sleep 20; }

# Keep the machine awake: the first matrix lost runs to the laptop sleeping.
caffeinate -dimsu -w $$ &

for MODE in polling sse; do
  say "== $MODE"
  base=$([ "$MODE" = polling ] && echo 0 || echo 1400)
  tests/load/backend.sh start $MODE >> "$LOG" 2>&1
  bench "latency_${MODE}" latency --mode $MODE --users 2000 --events 200 --warmup 20 --offset $base
  bench "burst_${MODE}_100" burst --mode $MODE --users 2000 --events 100 --warmup 20 --offset $((base + 200))
  bench "burst_${MODE}_1000" burst --mode $MODE --users 2000 --events 1000 --warmup 20 --offset $((base + 300))
done
tests/load/backend.sh start sse >> "$LOG" 2>&1
bench "leak" leak --cycles 20000 --concurrency 200
say "== done"
