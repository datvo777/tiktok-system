# Feed capacity benchmark

How many viewers the feed carries before PostgreSQL, Redis memory or the connection pool gives out, and
whether the choices made so far (reference-only ranking cache, a Redis of its own for it) move that limit.

## Why this is not a guess

A signed-in viewer's ranking is cached for the session TTL (`shortvideo.feed.ranking-session-ttl`, 30 min), not
the 30 s short TTL. Ranking memory is therefore about *rankings started per second x 1800 s x bytes per
ranking*, and every viewer who opens the feed and leaves still holds a key for the rest of the TTL. A run of a
few minutes never reaches that plateau, so `feed-bench.mjs` measures the rate and the key size and reports the
projection at the real TTL next to what Redis actually held.

## Run

```bash
docker compose -f infrastructure/docker-compose.yml up -d postgres redis redis-cache kafka kafka-init minio minio-init
mvn package -DskipTests
export BENCH_OUT=/some/dir                      # fresh: backend.sh keeps a private copy of the jar here
# scratch database: see the header of tests/load/backend.sh
node tests/load/seed.mjs --count 10000 --waiting 0 --out $BENCH_OUT/accounts.json
tests/load/seed-feed.sh A                       # profiles A, B, C: see the script
REDIS_CACHE_HOST=localhost REDIS_CACHE_PORT=6380 tests/load/backend.sh start polling   # cache on its own Redis
QUICK=1 tests/load/run-feed.sh                  # one small cell, to check the setup
tests/load/run-feed.sh                          # the matrix, about 3 hours
node tests/load/summarize-feed.mjs $BENCH_OUT/results-feed
tests/load/check-eviction.sh                    # C6, on its own
```

`run-feed.sh` takes the cache Redis from `.env` (`REDIS_CACHE_HOST`); `run-feed.sh` starts the backend itself
without those variables, so for the dedicated layout put them in `.env`.

## Matrix and criteria

Viewers 500, 2000, 5000, 10000 x profile A and C x 100% and 30% signed in, 3 runs each, 30 s warm-up, 90 s window.
A cell passes when: errors < 1%; Hikari pending <= 5; page-0 p95 <= 2x that of the lowest load of the same
profile and share; no eviction; memory (measured, and projected at the TTL) <= 70% of `maxmemory`.
These thresholds are proposals; change them before measuring, not after.

## What the viewer does

As `web/src/Feed.tsx`: page 0, a video every `--watchMs` (10 s), a view recorded after 3 s, the next page when
two slides are left, a new session after `--sessionVideos` (30). The first session starts at a random point so
that page 0 requests are spread across the window instead of all landing in the warm-up.

## Known limits of the harness

- PostgreSQL transactions per second include background work (outbox, schedulers): read it as a difference
  against the lowest load, not as the feed's own cost.
- `setsPerSec` counts every `SET` on the Redis it samples. On a shared Redis that includes the deny-list; use
  the dedicated cache Redis for the projection.
- Seeded videos go straight into the eligibility projection and social tables, not through upload and moderation.
- The client and the backend share one machine; `clientCpuCores` is reported so that a saturated load
  generator is visible.

## Checked while writing it (smoke runs, not results)

- A cached ranking of profile A is about 20 KB (`MEMORY USAGE`), against ~65 KB when it carried title and
  description. Profiles B and C are not measured yet.
- C6 reproduces on the old layout: one Redis at 32 MB under `allkeys-lru`, 3000 viewers' rankings, and a
  logged-out token is accepted again (`check-eviction.sh` prints BUG). With the cache on its own Redis the
  token stays refused.
