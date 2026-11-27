#!/usr/bin/env bash
# Samples a Redis every few seconds while something else loads it, one CSV line per sample.
#
#   tests/load/redis-sample.sh [container=sv-redis-cache] [interval_s=5] > redis.csv
#
# feed-bench.mjs takes the same readings itself; this is for watching a manual run or a different load.
set -uo pipefail
c="${1:-sv-redis-cache}"; every="${2:-5}"
echo "t,used_memory,maxmemory,evicted_keys,keyspace_hits,keyspace_misses,feed_ranking_keys,total_keys"
while true; do
  info=$(docker exec "$c" redis-cli info memory; docker exec "$c" redis-cli info stats)
  g() { echo "$info" | tr -d '\r' | awk -F: -v k="$1" '$1==k {print $2}'; }
  feed=$(docker exec "$c" redis-cli --scan --pattern 'feed:ranking:*' | wc -l | tr -d ' ')
  echo "$(date +%s),$(g used_memory),$(g maxmemory),$(g evicted_keys),$(g keyspace_hits),$(g keyspace_misses),$feed,$(docker exec "$c" redis-cli dbsize)"
  sleep "$every"
done
