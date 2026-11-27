#!/usr/bin/env bash
# C6: can memory pressure from feed rankings make a logged-out token work again?
#
#   tests/load/check-eviction.sh
#
# Shrinks the Redis the backend's deny-list lives on to 32 MB under allkeys-lru (what the compose file did
# before the cache was split out), logs a token out, fills that Redis with feed rankings, and asks again.
#   REDIS_CACHE_HOST empty (shared)  -> the deny-list entry competes with rankings; "BUG" means it was evicted.
#   REDIS_CACHE_HOST set (split)     -> rankings go elsewhere; the entry must survive.
# Needs the backend running from backend.sh against the scratch database, and accounts from seed.mjs.
set -uo pipefail
cd "$(dirname "$0")/../.."
: "${BENCH_OUT:?set BENCH_OUT}"
MAIN=sv-redis
BASE=http://localhost:8080
trap 'docker exec $MAIN redis-cli config set maxmemory 128mb >/dev/null; docker exec $MAIN redis-cli config set maxmemory-policy noeviction >/dev/null' EXIT

docker exec $MAIN redis-cli flushall >/dev/null
docker exec $MAIN redis-cli config resetstat >/dev/null  # so evicted_keys below is this run's
docker exec $MAIN redis-cli config set maxmemory 32mb >/dev/null
docker exec $MAIN redis-cli config set maxmemory-policy allkeys-lru >/dev/null

email="evict-$(date +%s)@example.com"
curl -s -o /dev/null -X POST $BASE/api/v1/accounts -H 'Content-Type: application/json' \
  -d "{\"email\":\"$email\",\"password\":\"correct-horse-battery\",\"displayName\":\"Evict\"}"
token=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$email\",\"password\":\"correct-horse-battery\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || { echo "could not log in (is the backend up, rate limits off?)"; exit 1; }
me() { curl -s -o /dev/null -w '%{http_code}' $BASE/api/v1/auth/me -H "Authorization: Bearer $token"; }

echo "before logout:  /auth/me -> $(me)   (expect 200)"
curl -s -o /dev/null -X POST $BASE/api/v1/auth/logout -H "Authorization: Bearer $token"
echo "after logout:   /auth/me -> $(me)   (expect 401)"

# Rankings from many viewers, until the main Redis has evicted (or 3 minutes pass).
node tests/load/feed-bench.mjs --users 3000 --signedIn 1 --watchMs 2000 --sessionVideos 6 --warmup 20 --window 40 \
  --redis $MAIN --out "$BENCH_OUT/evict-load.json" > /dev/null 2>&1
echo "rankings in main Redis: $(docker exec $MAIN sh -c "redis-cli --scan --pattern 'feed:ranking:*' | wc -l")"
echo "evicted keys on main Redis: $(docker exec $MAIN redis-cli info stats | tr -d '\r' | awk -F: '$1=="evicted_keys"{print $2}')"
code=$(me)
echo "after pressure: /auth/me -> $code"
if [ "$code" = 401 ]; then echo "OK: the logged-out token is still refused"; else echo "BUG: the logged-out token works again"; exit 1; fi
