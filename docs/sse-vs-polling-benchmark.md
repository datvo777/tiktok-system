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

## Deviations

(none yet)

---

## RESULTS
