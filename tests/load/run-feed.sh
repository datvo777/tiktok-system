#!/usr/bin/env bash
# The feed capacity matrix: viewers x data profile x signed-in share, 3 repetitions each, one JSON per run.
# Resumable: a run whose JSON exists is skipped.
#
#   BENCH_OUT=/some/dir tests/load/run-feed.sh
#   QUICK=1 BENCH_OUT=/some/dir tests/load/run-feed.sh      # one small cell, to check the setup
#
# Needs: compose stack up (postgres, redis, redis-cache), `mvn package -DskipTests`, the scratch database
# (see backend.sh), and $BENCH_OUT/accounts.json from `node tests/load/seed.mjs --count 10000 --waiting 0`.
# Signed-in viewers need one account each, so the largest cell needs 10,000.
#
# Which Redis the ranking cache uses follows .env: REDIS_CACHE_HOST set -> sv-redis-cache, else sv-redis.
set -uo pipefail
cd "$(dirname "$0")/../.."
: "${BENCH_OUT:?set BENCH_OUT}"
R="$BENCH_OUT/results-feed"; mkdir -p "$R"
LOG="$BENCH_OUT/progress-feed.log"
say() { echo "$(date +%H:%M:%S) $*" | tee -a "$LOG"; }

if grep -Eq '^REDIS_CACHE_HOST=.+' .env 2>/dev/null; then REDIS=sv-redis-cache; else REDIS=sv-redis; fi
say "ranking cache Redis: $REDIS"

if [ -n "${QUICK:-}" ]; then
  USERS_LIST="100"; PROFILES="A"; SHARES="1"; REPS=1; WARMUP=10; WINDOW=30
else
  USERS_LIST="500 2000 5000 10000"; PROFILES="A C"; SHARES="1 0.3"; REPS=3; WARMUP=30; WINDOW=90
fi

tests/load/backend.sh start polling >> "$LOG" 2>&1 || { say "backend did not start"; exit 1; }
for P in $PROFILES; do
  tests/load/seed-feed.sh "$P" >> "$LOG" 2>&1 || { say "seed $P failed"; exit 1; }
  for S in $SHARES; do
    for N in $USERS_LIST; do
      for r in $(seq 1 $REPS); do
        out="feed_${P}_in${S}_N${N}_r$r"
        [ -s "$R/$out.json" ] && { say "skip $out"; continue; }
        say "run $out"
        # Every run starts from an empty cache and fresh Redis counters, or the previous run's rankings
        # would be served as hits and its evictions counted again.
        docker exec "$REDIS" redis-cli flushall >/dev/null
        docker exec "$REDIS" redis-cli config resetstat >/dev/null
        node tests/load/feed-bench.mjs --users "$N" --signedIn "$S" --profile "$P" --redis "$REDIS" \
          --warmup $WARMUP --window $WINDOW --out "$R/$out.json" > "$R/$out.log" 2>&1 || say "FAILED $out"
        sleep 20
      done
    done
  done
done
say "== done; summarize with: node tests/load/summarize-feed.mjs $R"
