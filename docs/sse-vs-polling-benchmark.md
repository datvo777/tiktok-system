# SSE vs polling benchmark (plan item A5)

Status: **pre-registration written before any measurement was taken.** Results are appended below
the line marked `RESULTS`; nothing above that line is edited after data exists, except to record a
deviation under "Deviations" with the reason.

## Question

Does the realtime stream (`shortvideo.realtime.enabled`, `web/src/realtime.ts`) cost less than the
polling it sits next to, and by how much does it speed up what the user sees? SSE only says
"something changed, ask the API again"; REST remains the source of truth in both modes.

## Hypotheses (fixed in advance)

- **H1** In steady state, SSE cuts authenticated requests and backend CPU by at least 5x versus
  polling at the same number of users.
- **H2** Time from a change to the client knowing: polling p50 is several seconds (the poll
  interval); SSE p95 is under 2 s.
- **H3** Memory per SSE connection is tens of KB (measured, not assumed) and does not leak across
  100,000 open/close cycles.
- **H4** Mass reconnect after a restart is SSE's weak point: a higher peak than polling.

## Decision criteria (fixed in advance)

SSE is worth keeping if **all** hold:

- (a) steady-state backend CPU is not higher than polling at the same N,
- (b) p95 notice latency improves at least 3x,
- (c) a mass reconnect recovers in under 30 s with under 1% errors,
- (d) p95 of REST and of HLS streaming does not get worse by more than 20%.

Otherwise defer or drop it. If the criteria are met only partly, the report says which ones.

## What is compared

Per simulated user (one signed-in browser tab, its own connection):

| Mode | Inbox | Video status (only for users waiting on an upload) |
| --- | --- | --- |
| Polling (`?realtime=off`) | `GET /api/v1/notifications` every 10 s | `GET /api/v1/videos/{id}` every 2 s |
| SSE (`?realtime=on`) | one `GET /api/v1/events/stream`, plus the inbox every 60 s as the safety net; one extra inbox fetch (300 ms debounce) per hint and one on every (re)connect | unchanged: `Upload.tsx` keeps polling |

Because the video-status poll is the same in both modes, the saving SSE can claim is bounded by the
share of users who are waiting on an upload. S1 therefore reports two mixes: all users idle
(inbox only) and 10% of users waiting.

## Scenarios

- **S1** steady state, no events: N = 1000, 4000, 8000.
- **S2** latency: >= 200 real events (follow -> notification -> outbox -> Kafka -> client), timed
  from the follow request being sent to the target client noticing.
- **S3** bursts: 100 and 1000 events inside about 1 s, to different users.
- **S4** mass reconnect: restart the backend while N clients are connected.
- **S5** mixed load: N SSE users alongside HLS streams and REST.
- **S6** leak: 100,000 open/close cycles; connections and RSS must return to baseline.

## Conditions

- Same machine for both modes within one session; the polling baseline is re-measured in the same
  session, not taken from earlier numbers (those used 50 accounts and a C1-only JVM).
- Distinct accounts: one per simulated user (up to 8000), so the JWT filter's caches are not
  artificially hot. Separate database (`short_video_bench`), not the dev one.
- Backend run from the built jar, same JVM flags for every run, **without** `TieredStopAtLevel=1`.
- JWT TTL raised for the session so the cookies minted up front outlive the run; nothing here
  exercises refresh.
- Request counts come from the server's own `http_server_requests` counters; CPU from the
  process's CPU time over the window; errors keep their status codes.

## Known limitations of this design (stated up front)

- Load generator, backend, Postgres, Redis, Kafka and MinIO share one laptop. The generator's own
  CPU is measured and reported next to the backend's.
- One macOS loopback host: 16,384 ephemeral ports and `kern.ipc.somaxconn=128` bound what one
  generator can open, and TIME_WAIT limits connection churn.
- No real proxy in front (nginx buffering/timeouts are not exercised); the Vite dev proxy was
  checked separately and does not buffer.

---

## Deviations (recorded after the fact; none of them changes a hypothesis or a criterion)

- Windows are **90 s after 30 s of warm-up**, not 5 min after 60 s, so the whole matrix fits in a few
  hours. 90 s still spans one full 60 s inbox cycle of the SSE mode. Reps are 3 per cell, with one cold
  JVM start per cell rather than per rep.
- Users waiting on an upload poll their video every 2 s throughout (the client's first-minute cadence).
- The first full matrix ran on a jar with a defect (see "Defect found by the benchmark"); it was thrown
  away and the matrix re-run on the fixed jar. Only the second matrix is reported, except where noted.
- The laptop slept or stalled during parts of the second run: three steady reps show multi-minute
  durations or connection resets (polling N=1000 rep 1, N=4000 rep 3 which was lost, N=8000 rep 1).
  Cells report the **median with min-max**, so a stalled rep shows in the range instead of the middle.
- **S2 (latency)**: the polling figure is 200 events. The SSE figure is only **30 events**, from a
  separate fresh-JVM run, because the full SSE run was cut short (see "Not completed").
- **S3 (bursts), S5 (mixed HLS load) and S6 (leak) were not completed or are not valid**; see below.
- First S2/S3 runs reused follower/target pairs across runs. A repeated follow is a no-op that raises no
  event, so those results (including the "0 hints received" in SSE mode) were a defect of the load tool,
  not of SSE, and are discarded.

---

## RESULTS

Run on 2026-10-08/09, one laptop (Mac16,6, 14 cores, 36 GB, macOS 26.6), backend from the built jar
(`java -Xms2g -Xmx4g -XX:+UseG1GC -XX:-MaxFDLimit`, JDK 25.0.1), Postgres/Redis/Kafka/MinIO in Docker on
the same machine, load generated by one Node process (`tests/load/`), 8000 distinct accounts, scratch
database `short_video_bench`. Tomcat `max-connections` was lifted to 40,000 for the runs (see finding 4).

## Defect found by the benchmark (fixed)

The first matrix produced nonsense for SSE (no hints delivered) and once left the backend wedged: 0% CPU,
the metrics endpoint timing out, 250 sockets in CLOSE_WAIT, and the JVM ignoring SIGTERM. A thread dump
showed thousands of request virtual threads blocked in class loading under
`JwtAuthenticationFilter -> JwtService.parse -> Jwts.parser()...build() -> ServiceLoader`.

`build()` resolves its providers through `ServiceLoader`, which scans the classpath, and the code did
that on **every authenticated request** (also in `PlaybackTokenService.parse`, which sits on the HLS
segment path). From the executable jar the scan goes through the nested-jar loader's shared locks and,
under many concurrent virtual threads, the server stopped answering. The dev path (`spring-boot:run`,
exploded classes) never showed it. The parser is now built once in the constructor of both services.

Same scenario (300 users, 20 events, cold JVM), jar before vs after: **0 of 20 hints delivered** (once
the server also wedged completely) vs **20 of 20**. After the fix no wedge occurred in the second matrix.
It is not a CPU optimisation at this request rate: polling N=4000 used 0.42-0.45 cores before and
0.48-0.51 after (noise level), SSE 0.17-0.24 vs 0.19-0.25.

## S1 steady state (second matrix, fixed jar; median of 3 reps unless noted, min-max in the raw JSON)

All users idle (inbox only), requests counted by the server:

| Users | Mode | Server req/s | Backend CPU (cores) | RSS MB | Open files | Inbox p50/p95/p99 as the client saw it (ms) | Server avg inbox (ms) |
|---|---|---|---|---|---|---|---|
| 1000 | polling | 100 | 0.19 | 1884 | 1182 | 13 / 29 / 32 | 10-13 (one stalled rep 85) |
| 1000 | SSE | 23 | 0.08 | 2004 | 2189 | 13 / 18 / 21 | 8-12 |
| 4000 | polling (2 reps) | 400 | 0.51 | 1915 | 4182 | 11 / 18 / 23 | 9-10 |
| 4000 | SSE | 89 | 0.20 | 2555 | 8187 | 13 / 28 / 47 | 12 |
| 8000 | polling | 800 | 0.68 | 2193 | 8182 | 7 / 17 / 23 | 7 (one stalled rep 569) |
| 8000 | SSE | 137 | 0.28 | 3017 | 16167 | 13 / 42 / 74 | 13-14 |

With 10% of users waiting on an upload (N=4000), whose video poll is the same in both modes:
polling 600 req/s, 0.59 cores; SSE 289 req/s, 0.43 cores.

Zero errors in polling. SSE showed a small number of `ECONNRESET` on inbox requests (8-103 of ~8,000 per
run): the Node client reusing a keep-alive socket the server had just closed, which a browser would
retry silently; not a server failure.

## S2 time until the client knows

| Mode | Events | Reached the client | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| polling (inbox every 10 s) | 200 | 200 | 5.4 s | 9.8 s | 10.3 s | 10.3 s |
| SSE, hint arrives | 30 | 30 | 1.03 s | 1.06 s | 1.06 s | 1.06 s |
| SSE, inbox refetched after the 300 ms debounce | 30 | 30 | 1.34 s | 1.37 s | 1.37 s | 1.37 s |

The ~1 s for SSE is the pipeline (follow -> social event -> notification -> outbox -> Kafka -> hint), not
the stream. The 30-event SSE sample is small.

## S4 restart while 4000 users are connected

| Mode | Back to 90% / 99% healthy after the server is up | Backend CPU peak | Hikari pending peak | Inbox req/s peak | Client-side failed attempts |
|---|---|---|---|---|---|
| polling | 7.2 s / 8.2 s | 1.8 cores | 0 | 460 | 1,767 refused (during the 6 s outage), 13 reset |
| SSE | 3.2 s / 3.2 s | **6.1 cores** | **1,101** | **2,657** | 7,339 stream-open resets, 86 refused, 584 inbox resets |

Control (restart with no clients): 1.1-1.2 cores, so start-up cost alone does not explain the SSE peak.
SSE recovers sooner in wall time (the browser retries after ~3 s instead of waiting out a 10 s poll) but
as one synchronised wave: every client reconnects and refetches at once.

## Memory per connection and leak check (from the **first** run, before the fix; the fix does not touch this)

| Step | RSS MB | Heap MB after forced GC | realtime.connections | Open files |
|---|---|---|---|---|
| baseline | 1713 | 122 | 0 | 188 |
| 1000 users connected | 1844 | 264 | 1000 | 2189 |
| 3000 users connected | 2162 | 497 | 3000 | 6188 |
| 4099 users connected (6000 attempted) | 2401 | 640 | 4099 | 8378 |
| after releasing, then 100,000 open/close cycles (concurrency 200, 1.7% failed) | 2504 | 935 | **0** | **172** |

About **125-140 KB of heap and 130-150 KB of RSS per connected user**, where a user holds two sockets
(the stream, and a keep-alive one for REST). Connections and descriptors returned to baseline after the
churn. Heap after the churn was not back to baseline after one forced GC (935 vs 122 MB), so "no leak"
is **not established** for memory; only for connections and descriptors. The 6000-user step reached only
4099 because a single destination address runs out of ephemeral ports (later worked around).

## Not completed

- **S3 bursts (100 and 1000 events in 1 s): no valid result.** The first attempt reused pairs; the
  re-run was interrupted when the report was requested.
- **S5 (SSE alongside HLS streams and REST): not run.** Needs a published video, the worker and ffmpeg.
  Consequently criterion (d) is only partly assessed (REST, not HLS).
- **S6**: only the first run's numbers above; the re-run with timeouts was interrupted.
- **Full-size SSE latency run (200 events): not completed**; 30 events used.

## Findings that are not part of the hypotheses

1. **Two connections per SSE user.** Over HTTP/1.1 the open stream occupies one connection, so REST
   uses a second. Open files: 8,187 vs 4,182 at N=4000, 16,167 vs 8,182 at N=8000.
2. **JVM file-descriptor ceiling on macOS**: the JVM caps itself at 10,240 descriptors unless started
   with `-XX:-MaxFDLimit`, below what ~5,000 SSE users need. (A Linux deployment has its own `nofile`
   limit to raise.)
3. **Accept queue**: with `somaxconn=128`, connecting thousands of clients at once produced the resets
   seen above; ramp-up and reconnection need to be paced.
4. **Tomcat `max-connections` defaults to 8,192.** At two connections per user that is ~4,000 SSE users
   (against ~8,000 polling users), unless raised or HTTP/2 is used.
5. The load tool itself: a Node process holding ~10k sockets cannot spawn children on macOS, and one
   destination address has only 16,384 ephemeral ports. Both are worked around in `tests/load/`.

## Hypotheses

- **H1 (>= 5x fewer authenticated requests and CPU): partly.** Requests: 4.3x (N=1000), 4.5x (N=4000),
  5.8x (N=8000) fewer when idle. CPU: only ~2.4-2.6x fewer, because a base cost does not shrink. With 10%
  of users waiting on an upload, requests fall 2.1x and CPU 1.4x, since the video poll is unchanged.
- **H2 (polling p50 several seconds; SSE p95 < 2 s): met**, on a 30-event SSE sample.
- **H3 (tens of KB per connection, no leak): not met on size, partly on leak.** 125-150 KB per user
  (two sockets), not tens of KB; connections and descriptors returned to baseline, heap not shown to.
- **H4 (mass reconnect is the weak point): confirmed.** 3.4x the CPU peak and 1,101 queued DB requests
  against none for polling.

## Decision against the pre-registered criteria

| Criterion | Result |
|---|---|
| (a) steady CPU not higher than polling | **met**: lower at every N (0.08 vs 0.19, 0.20 vs 0.51, 0.28 vs 0.68 cores) |
| (b) p95 notice latency improved >= 3x | **met**: ~9.8 s vs ~1.1 s (small SSE sample) |
| (c) mass reconnect recovers < 30 s with < 1% errors | **partly**: recovery 3.2 s, but thousands of failed connection attempts for 4,000 users (the stream-open resets), and a 6-core, 1,101-queued spike |
| (d) REST p95 and HLS p95 not worse by > 20% | **not met on REST**: inbox p95 28 vs 18 ms (N=4000) and 42 vs 17 ms (N=8000) as the client saw it, server average 12-14 vs 7-10 ms; HLS not measured |

Because (c) and (d) are not met, the pre-registered rule says **defer, keep the flag off**.

What the numbers support saying: SSE is a clear win on notice latency (about 1 s instead of 5-10 s) and
on idle request volume (4-6x), and a modest win on idle CPU. At up to 8,000 signed-in users polling costs
only ~0.7 cores, so the absolute saving (~0.4 cores) is small next to what SSE adds: twice the
descriptors, 25-35% more resident memory, a Tomcat connection cap that bites at ~4,000 users, and a
reconnection storm that queues the database pool. The case for enabling it is latency, not load.

If it is enabled later: raise `max-connections` and the descriptor limit, add jitter to the first
reconnect after a restart (the client already jitters after a refusal, not after a dropped stream),
rate-limit or stagger the `onopen` refetch, and consider HTTP/2 so the stream and REST share one
connection. Re-run S3, S5 and S6 first.

## Reproducing

```
mvn package -DskipTests
docker exec sv-postgres psql -U shortvideo -d postgres -c "CREATE DATABASE short_video_bench" \
  -c "GRANT CONNECT, CREATE ON DATABASE short_video_bench TO short_video_app"
export BENCH_OUT=/some/dir
tests/load/backend.sh start sse
node tests/load/seed.mjs --count 8000 --waiting 800 --out $BENCH_OUT/accounts.json
tests/load/run-all.sh        # steady, latency, burst, restart, leak
tests/load/run-events.sh     # event scenarios with fresh follower pairs
node tests/load/summarize.mjs $BENCH_OUT/results
```
Session cookies are minted once and last 8 hours; re-seed after that.
