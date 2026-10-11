#!/usr/bin/env bash
# Candidates for making the realtime stream cheaper, at 4000 users of whom 10% are waiting on an upload.
#
#   P   polling only (baseline)
#   S0  stream for everyone, Tomcat keep-alive at its default, video polled every 2 s (as built)
#   S1  S0 with the idle keep-alive connection closed after 5 s
#   S2  S1 with the video poll relaxed to 15 s while the stream is up
#   D   S2 but only users waiting on an upload hold a stream; the rest poll the inbox every 10 s
#   D2  D with the others polling the inbox every 30 s
#
# REPS=1 NAMES="P S2 D D2" selects what to run.
#
#   BENCH_OUT=/some/dir tests/load/run-cheaper.sh      # needs >= 4000 accounts, 400 with a draft video
set -uo pipefail
cd "$(dirname "$0")/../.."
: "${BENCH_OUT:?set BENCH_OUT}"
R="$BENCH_OUT/results-cheaper${SUFFIX:-}"; mkdir -p "$R"
LOG="$BENCH_OUT/progress-cheaper.log"
say() { echo "$(date +%H:%M:%S) $*" | tee -a "$LOG"; }
caffeinate -dimsu -w $$ &

run() { # name mode keepalive args...
  local name="$1" mode="$2" ka="$3"; shift 3
  for r in $(seq 1 "${REPS:-2}"); do
    [ -s "$R/${name}_r$r.json" ] && { say "skip ${name}_r$r"; continue; }
    say "run ${name}_r$r"
    if [ "$ka" = default ]; then unset SERVER_TOMCAT_KEEP_ALIVE_TIMEOUT; else export SERVER_TOMCAT_KEEP_ALIVE_TIMEOUT=$ka; fi
    tests/load/backend.sh start "$mode" >> "$LOG" 2>&1
    node tests/load/bench.mjs steady --mode "$mode" --users 4000 --waiting 0.1 --warmup 30 --window 90 "$@" \
      --out "$R/${name}_r$r.json" > "$R/${name}_r$r.log" 2>&1 || say "FAILED ${name}_r$r"
    sleep 15
  done
}

want() { [[ " ${NAMES:-P S0 S1 S2 D D2} " == *" $1 "* ]]; }
want P  && run P  polling default
want S0 && run S0 sse default
want S1 && run S1 sse 5s
want S2 && run S2 sse 5s --videoMs 15000
want D  && run D  sse 5s --videoMs 15000 --streamFor waiting
want D2 && run D2 sse 5s --videoMs 15000 --streamFor waiting --idleInboxMs 30000
tests/load/backend.sh stop >> "$LOG" 2>&1
say "== done"
