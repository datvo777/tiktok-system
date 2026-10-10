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

## Reconnect-storm defences (S4 re-measured after they were built)

The first S4 showed SSE's weak point: restarting the backend with 4,000 connected users sent the CPU to
6.1 cores and queued 1,101 requests on the database pool, against 1.8 cores and none for polling. The
defences are in `StreamOpenGate` (server, ahead of authentication) and `RealtimeConnection` (client);
see the commit that added them. Same test as S4 (4,000 users, restart, 60 s of observation), with a REST
probe (an account outside the crowd, asking for its inbox every 250 ms) running alongside.

| Variant | Client | Server gate | Back to 90% / 99% | CPU peak | Hikari pending peak | Inbox req/s peak | REST probe p95 / max |
|---|---|---|---|---|---|---|---|
| V0 | old (fixed ~3 s retry) | off | 3.2 s / 28.7 s | **6.8 cores** | **497** | **2,317** | 20 ms / **511 ms** |
| V1 | spread (wide jittered window) | off | 28.7 s / 36.9 s | 1.4 | 0 | 289 | 16 ms / 115 ms |
| V2 | old | on (200 opens/s, 6 in flight) | 26.6 s / 29.6 s | 1.6 | 0 | 328 | 18 ms / 31 ms |
| V3 | spread | on | 30.8 s / 39.0 s | 1.9 | 0 | 304 | 14 ms / 116 ms |
| (polling, from S4) | | | 7.2 s / 8.2 s | 1.8 | 0 | 460 | not measured |

Control: restarting with no clients costs 1.1-1.4 cores, so about 1.2 of every figure above is the JVM
starting.

What it shows (one run per variant, not repeated):

- **Either layer alone removes the spike.** The client change (V1) and the gate (V2) each bring the peak
  from 6.8 cores to about 1.5 and the pool queue from 497 to 0, which is polling's level (1.8 cores, 0).
  They are redundant in this test because the test client cooperates; the gate is what protects against
  a client that does not (V2 is an old client), and the client change is what keeps refusals from being
  needed at all (V2 turned away 3,835 opens with a 503 and had 4,000 refused connections during the
  outage; V3 refused 45).
- **The price is recovery time**, accepted in advance: 90% of users are back after about 27-31 s rather
  than 3 s, and 99% after 30-39 s. It is the same effect that makes polling's peak low: spreading.
  Against the plan's criterion of "95% within 30 s" the variants are borderline (p95 was not measured
  directly; p90 is 27-31 s).
- **REST is unharmed in every variant at the 95th percentile**, but without any defence the worst probe
  request took 511 ms against 31-116 ms with them.

Caveats: the load tool imitates `EventSource` (it honours the `retry:` value the server sends, as the
spec says a browser does); that has not been confirmed in Chrome, Firefox or Safari. The "failed attempts"
counted by the tool include the connections reset when the old process shuts down, so the 503 and
refused counts above are the cleaner measure of refused opens.

## Making SSE cheaper (resource cost re-measured with candidate fixes)

The first comparison put SSE's price at twice the connections and descriptors, 25-35% more resident
memory, and a Tomcat connection cap (8,192) reached at about 4,000 users. Candidates, measured at 4,000
signed-in users of whom 10% wait on an upload, 90 s windows, backend from the jar:

- **P** polling only. **S0** stream for everyone as first built. **S1** S0 with Tomcat's idle keep-alive
  closed after 5 s. **S2** S1 with the status polls relaxed from 2 s to 15 s while the stream is up (a
  hint already announces READY or FAILED, so the poll is only a safety net). **D** S2 but only users
  waiting on an upload hold a stream; the rest poll the inbox every 10 s. **D2** D with the rest at 30 s.

First pass (2 reps each, median; req/s is the server's own count):

| | req/s | of which inbox / video | Backend CPU | Open files | Streams |
|---|---|---|---|---|---|
| P | 600 | 400 / 200 | 0.57 | 4,196 | 0 |
| S0 | 289 | 89 / 200 | 0.42 | 8,187 | 4,000 |
| S1 | 289 | 89 / 200 | 0.42 | 5,352 | 3,999 |
| S2 | **116** | 89 / 27 | **0.26** | 5,142 | 4,000 |
| D | 396 | 369 / 27 | 0.45 | 2,887 | 400 |

Second pass (1 rep, with a forced GC before reading the heap, which the first pass lacked):

| | req/s | CPU | Open files | Live heap after GC | Inbox p95 / video p95 |
|---|---|---|---|---|---|
| P | 600 | 0.57 | 4,189 | 436 MB | 13 / 10 ms |
| S1 | 289 | 0.40 | 5,327 | 682 MB | 17 / 12 ms |
| S2 | 116 | 0.26 | 5,162 | 605 MB | 22 / 14 ms |
| D | 396 | 0.49 | 2,971 | 349 MB | 11 / 9 ms |
| D2 | 156 | 0.33 | 1,494 | 173 MB | 16 / 12 ms |

What it shows (heap figures carry roughly +-150 MB of noise from a single run; the rest is stable):

- **Closing the idle keep-alive connection (S0 -> S1)** takes descriptors at 4,000 users from 8,187 to
  5,352 (-35%) at no CPU cost. An earlier run without waiting users saw resident memory fall 20% too;
  here it did not, so treat the memory effect as unproven.
- **Relaxing the status polls while the stream is up (S1 -> S2)** is the big one: 200 of the 289 req/s
  were status polls. Total requests fall 80% and backend CPU 54% below polling (0.26 vs 0.57 cores).
- **Stream only for those waiting (D, D2)** gives the fewest connections and the smallest heap, but the
  others learn about likes, comments and follows only by polling (10 s or 30 s); at 30 s that is three
  times slower than polling is today. It trades the user-visible benefit of SSE away for resources.
- Live heap for a stream is about 45-60 KB (S2 minus P, 4,000 streams), well under the 125-150 KB per
  user measured before the keep-alive change.

Chosen: **S2**. It has the lowest request rate and CPU of everything tried, keeps notifications near
instant for everyone, and costs 23% more descriptors than polling instead of 95% more. It is also the
smallest change. D remains the lever to pull if one instance must hold more than about 6,000 signed-in
users: it needs the client to know when a user is waiting, which is more logic than this was worth.

Built: `server.tomcat.keep-alive-timeout` (`TOMCAT_KEEP_ALIVE_TIMEOUT`, unset by default; set it to `5s`
only when browsers reach Tomcat directly, because behind a proxy a value shorter than the proxy's
upstream keepalive makes it reuse connections Tomcat just closed), and the status and list polls now
relax to a 15 s safety net whenever the stream is connected. A tab hidden for 60 s already closes its
stream, so in real use fewer than all signed-in users hold one.

Not measured: HTTP/2 through a reverse proxy (one browser connection for stream and REST), one stream
per browser rather than per tab, and narrower Tomcat buffers.


## Decisions taken from these numbers

- **No in-process cache for the "account still active" check.** With the storm defences on (V1-V3) the
  Hikari queue peaked at 0 and the REST probe p95 stayed at 14-18 ms, so the per-request revocation
  query is not what limited S4; the unspread reconnect wave was. A cache would only buy back a few
  milliseconds in steady state, and costs the property that a suspension takes effect at once when
  Redis is down (Rule 12). Revisit only if S1 at 8,000 users shows `hikaricp_connections_pending > 0`.
- **A dependency failure while judging a token answers 503 + `Retry-After`, not 401.** Still refused
  (Rule 9), but the web client treats 401 as a lost session and signs the user out, which a database
  blip must not do. Set by `JwtAuthenticationFilter.AUTH_STATE_UNAVAILABLE`, answered in `SecurityConfig`.
- **Open item against criterion (d):** inbox p95 under S2 is 22 ms against polling's 13 ms. The absolute
  cost is small but the pre-registered 20% bound is not met; S3, S5 and S6 are still unmeasured.

## Re-measurement, 2026-10-10 (S3, S6, and the two oddities)

Backend: `a89047f` plus the uncommitted heartbeat-as-event, graceful shutdown and 503-on-unreadable-state
changes, run from the jar, one laptop, no proxy or TLS. Gate on, `shortvideo.realtime` at its defaults.
SSE runs use the benchmark client's own cadences (video polled every 2 s), not the relaxed S2 cadences.

### S3 burst, 2,000 users, events sent in about 1 s

| Mode | Events | Received | Noticed p50 / p95 / p99 (ms) | Peak CPU | Hikari pending peak |
|---|---|---|---|---|---|
| polling | 100 | 100 | 1136 / 1286 / 1316 | 0.52 | 0 |
| sse | 100 | 100 | 1117 / 1364 / 1388 | 0.39 | 0 |
| polling | 1000 | 1000 | 4137 / 6745 / 6967 | 1.62 | 214 |
| sse | 1000 | 1000 | 1089 / 1299 / 1334 | 1.61 | 439 |

No event was lost in either mode. Under 1,000 events in one second SSE keeps its notice latency near
1.1 s where polling slips to 4-7 s, at the same CPU peak. The Hikari queue peaks in both (214 and 439);
the load generator's 1,000 follow requests land on the same pool in that second, so the figure is not
attributable to SSE alone. The single-event latency run agrees with the first matrix: 5.5 / 9.8 s for
polling against 1.1 / 1.3 s for SSE (p50 / p95).

### S6 leak check (first attempt invalid, then redone)

The first run's churn was refused 19,769 times out of 20,000 by the open gate and the per-account
limiter, so it measured the gate, not cleanup; its release wait (25 s) was also too short for 5,900
dead sockets. `leak()` now paces the churn under the gate (`--rate`), waits 60 s after a release, and
records `GC.class_histogram` before and after.

- **Paced churn, 60,000 open/close cycles at 120/s:** 43 failed (17 of them 503), peak 4,855 registered
  connections, then **0 connections and 200 open files** (baseline 0 and 189). A closed client stays
  registered until the next heartbeat write fails, up to about 20 s, which is where that peak comes from.
- **Five identical hold-3,000/release rounds:** live heap after GC and release 436, 433, 429, 431, 432 MB;
  RSS 2,380-2,390 MB throughout; connections and files back to baseline every time. **Flat, so not a leak.**
  The first round raises the floor from about 100 MB to about 430 MB and it stays there: `byte[]` and
  `char[]` plus Tomcat's `ByteChunk`/`CharChunk`/`MessageBytes`, which look like pooled buffers sized to
  the peak. Why Tomcat keeps that much was not investigated; it is a one-time cost, not growth.

### The two oddities

- **REST p95 42 ms vs 17 ms at 8,000 users did not reproduce.** Two reps each, client-observed:

  | Users | Mode | Inbox p50 / p95 / p99 | Backend CPU | Server req/s | Hikari pending | Errors |
  |---|---|---|---|---|---|---|
  | 4,000 | polling | 8 / 14.7 / 19 | 0.64 | 600 | 0 | 0 |
  | 4,000 | sse | 9.5 / 24.5 / 38 | 0.47 | 289 | 0 | 0 |
  | 8,000 | polling | 7.3 / 17.1 / 23 | 0.81 | 1,200 | 28 | 0 |
  | 8,000 | sse | 7.8 / 23.8 / 50 | 0.57 | 538 | 0 | 0-1 |

  SSE's inbox p95 is 7-10 ms higher (+67% at 4,000, +39% at 8,000), so criterion (d) is still **not met**
  on the 20% rule, but it is a stable gap of a few milliseconds and not the 42 ms seen once. The pool is
  never queued under SSE; it is under polling at 8,000 (28 pending). Part of the gap may be the load
  generator, which holds up to 8,000 streams in one process; it was not separated out.
- **Polling at 4,000 failing 22% with 3.8 GB RSS did not reproduce:** 0 errors and about 1.9 GB RSS in
  both reps. Still unexplained; treat it as a one-off of the first matrix, not as a result.

Steady-state totals agree with the first matrix: SSE needs about half the requests and 27-30% less CPU
than polling (0.47 vs 0.64 cores at 4,000; 0.57 vs 0.81 at 8,000), for twice the open descriptors.

### Not measured

- **S5 (SSE alongside 20-30 HLS streams and REST).** `bench.mjs` has no such scenario, and it needs a
  published, transcoded video in the scratch database and storage. Whether the gateway's 503s or REST
  p95 move with SSE load is therefore unknown.
- Chrome, Firefox and Safari behaviour of `retry:` and of the new `ping` event; the silence watchdog is
  covered by unit tests only.
- Anything through a reverse proxy or real network latency.

Conclusion unchanged: the flag stays off. Criterion (d) is still missed by a few milliseconds and S5 is
open; the case for enabling remains notice latency (about 1.1 s against 5-10 s, also under a burst), not load.

### S5: SSE alongside HLS viewers and REST (2026-10-10)

Setup: a real 2 s clip uploaded through the pipeline (media-worker, ffmpeg), approved by a scratch admin
and published; 30 viewers fetch its segment (`/media/videos/{id}/1/low/segment_000.ts`, about 128 KB)
through the gateway in a loop with a 100 ms pause, which is much tighter than a player; a user outside
the crowd asks for its inbox every 250 ms (the REST probe); 4,000 users, 10% waiting on an upload, 30 s
warm-up and a 90 s window, two reps per row, SSE with the benchmark client's 2 s video poll. `control`
is the HLS viewers and probe with no crowd; it ran on a cold JVM (no traffic to warm the JIT before the
window), so it is slower than the other two and is not a baseline to subtract.

| Run | HLS segment p50 / p95 / p99 / max (ms) | HLS non-200 | REST probe p50 / p95 / p99 (ms) | Backend CPU | Server req/s |
|---|---|---|---|---|---|
| control r1 / r2 | 10 / 14.7 / 22 / 43 ; 11 / 16.5 / 23 / 38 | 0 | 4.4 / 8.6 / 10 ; 4.6 / 9.3 / 11 | 0.70 ; 0.72 | 276 ; 272 |
| polling r1 / r2 | 5.6 / 10.4 / 13.5 / 22 ; 7 / 12.7 / 21 / 359 | 0 | 2.2 / 4.2 / 7.5 ; 2.2 / 5.2 / 28 | 0.82 ; 0.86 | 885 ; 880 |
| sse r1 / r2 | 7.1 / 12.7 / 20 / 112 ; 6.2 / 10.8 / 18 / 116 | 0 | 2.5 / 5.1 / 10 ; 2.3 / 5.6 / 21 | 0.71 ; 0.68 | 570 ; 573 |

- **The gateway is not affected by the crowd's transport.** About 25,000 segment fetches per run, every
  one a 200; p95 is 10.4 / 12.7 ms under polling and 12.7 / 10.8 ms under SSE (+2% on the means), which
  is inside the spread between reps of the same mode.
- **The REST probe's p95 is 4.2 / 5.2 ms under polling and 5.1 / 5.6 ms under SSE** (+14% on the means,
  under the 20% bound, on two reps and about 355 requests each). Its p99 and max are noisy in both modes.
- Neither mode queued the Hikari pool. SSE used 17% less CPU than polling here (0.70 vs 0.84) with the
  HLS load added on top. The SSE max latencies (112 and 116 ms against 22 and 359 for polling) are single
  requests, not a pattern; the sample is too small to say more.

What this does not test: 30 viewers stay under the gateway's 32 concurrent-stream permits and the segment
is small, so the 503 path of the gateway was **not** exercised and larger segments (more time per permit)
were not tried. Real players ask once per segment duration, so this load is heavier than reality, not
lighter. One clip, one resolution, a laptop, no proxy.

Reading criterion (d) with this: REST measured by an outside user and HLS stay within 20% of polling.
The earlier miss was the crowd's own inbox p95 as the load generator saw it (24 ms against 15-17 ms),
which includes the generator holding 4,000-8,000 open streams in one process; that gap is real in the
numbers but is not shown to reach a user outside the crowd or the gateway.
