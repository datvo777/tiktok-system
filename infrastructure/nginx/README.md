# nginx in front of the backend

A reverse proxy for the built web app, the API and the realtime stream. It is optional: the Compose
service sits behind the `proxy` profile, so a plain `up -d` does not start it. Day-to-day development
still uses the Vite dev server, which proxies `/api`, `/internal` and `/media` itself.

```bash
(cd web && npm run build)          # nginx serves web/dist
docker compose --env-file .env -f infrastructure/docker-compose.yml --profile proxy up -d nginx
# http://localhost:8088   (NGINX_PORT to change it)
```

The backend runs on the host, so the upstream is `host.docker.internal:8080`.

## Settings the backend must match

| Backend | Why |
|---|---|
| `SHORTVIDEO_RATE_LIMIT_TRUST_FORWARDED_FOR=true` and `SHORTVIDEO_RATE_LIMIT_TRUSTED_HOP_COUNT=1` | Behind a proxy every request comes from the proxy's address; without this the per-IP login and register limits become one shared bucket. nginx overwrites `X-Forwarded-For` with the client address, which is what a hop count of 1 reads. |
| `TOMCAT_KEEP_ALIVE_TIMEOUT` unset (60 s) | The upstream pool's idle timeout (30 s) must be shorter than Tomcat's, otherwise nginx reuses a connection Tomcat just closed and answers 502. Do not set the 5 s value from the benchmark notes when a proxy is in front. |
| `REALTIME_ENABLED=true` and a web build with `VITE_REALTIME=true` | Both sides, or no stream is opened. Off by default. |

On Docker Desktop the client address nginx sees is the VM's gateway, not the browser's, so locally every
request looks like it comes from one address even with the settings above.

## What is configured, and why

- **The stream has its own location** (`/api/v1/events/stream`): no buffering, no compression, a one-hour
  read timeout (between two reads, so the server's 20 s ping keeps it alive), GET only, no access log.
- **`/internal`, `/actuator` and the API docs return 404** at this edge.
- **Hashed assets are cached for a year, `index.html` is not**; unknown paths fall back to `index.html`.
- **Fail fast to the backend** (`proxy_connect_timeout 5s`); the log format records `uct`, the connect time
  to the backend, where a non-zero value means a request opened a new connection instead of reusing one.
- **The container is read-only** with tmpfs for the paths nginx writes, and has a descriptor limit of 65,535.

## What is measured and what is not

Measured (see `docs/sse-vs-polling-benchmark.md`): about three descriptors per streaming user on nginx
(12.4k for 4,000), Tomcat's descriptors halving to one per stream, no change in backend CPU or REST p95,
the stream working with buffering at its default because of `X-Accel-Buffering: no`, and a reconnect
after a backend restart, a hidden tab and a half-open connection through nginx in a real browser.

**Not measured, so treat the numbers as starting points:**

- `keepalive 64` per worker. It is the number of idle connections kept, not a cap. Look at `uct` in the
  access log under real load: the share of requests with a non-zero value is the share that opened a new
  connection. With 4,000 users it was 6% over a whole run, but 16-20% of the requests logged while the
  users were still arriving and about 0.1-2% afterwards; judge it in steady state, and raise it if that
  share stays above about 1%.
- `worker_connections 16384` and `worker_rlimit_nofile 65535` are sized for the few thousand users the
  benchmarks reached on one node. A node holding tens of thousands of streams needs both raised, and the
  host's `LimitNOFILE` and `fs.nr_open` with them (about three descriptors per user).
- `keepalive_timeout 20s` towards browsers, `client_max_body_size 2m`, and the default buffering on
  `/media/` for large segments.
- TLS, HTTP/2, several nginx nodes, and anything above the load the benchmarks used.
- Behaviour on Safari and Firefox.

**A problem seen only with this proxy on Docker Desktop:** about 300 times per 4,000-user run nginx logs
`upstream timed out (110: Connection timed out) while connecting to upstream`, all while users were
still arriving (new upstream connections), and almost none afterwards. With `proxy_connect_timeout 5s`
those requests fail in 5 s and the clients retry (all 4,000 streams were open at the end of both runs);
with nginx's default 60 s they showed up as 2-4% of stream opens failing with a 504 in the window. The
same ramp straight to the backend, without the container, produced no connect timeouts, which points at
the container-to-host path of Docker Desktop and not at this configuration or Tomcat. That has not
been confirmed; run the proxy on the same host as the backend (Linux, or nginx installed natively)
before treating the rate as a property of nginx.

Not done here: security headers (the backend sets them already), rate limiting at the proxy
(`limit_conn` per address would hurt users behind a shared address), and TLS termination.
