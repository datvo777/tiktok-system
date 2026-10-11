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

## mTLS for the admin host (experiment)

Mutual TLS is decided per hostname, so users and staff can share one nginx and one port: the public name
asks for no client certificate and the admin name requires one. The admin app and `/internal` are not
served on the public name at all (`/internal` is a 404 there).

```bash
infrastructure/nginx/mtls/gen-dev-certs.sh                       # throwaway CA and certificates
(cd web && npm run build); (cd admin-web && npm run build)
docker compose --env-file .env -f infrastructure/docker-compose.yml --profile mtls up -d nginx-mtls
# https://tksystem.localhost:8443 (users)   https://admin.tksystem.localhost:8443 (staff, needs a client certificate)
```

`*.localhost` resolves to this machine in browsers; the server certificate is self-signed, and a browser
needs the staff certificate imported before the admin name will work (not tried in a browser).

Tested with curl from inside the container (nginx 1.29.4, the backend running):

| Request | Result |
|---|---|
| public name, no certificate | 200 |
| public name, `/internal/...` | 404 |
| admin name, no certificate | 400, from nginx; the backend is never reached |
| admin name, certificate from a CA nginx does not trust | 400 |
| admin name, valid staff certificate | 200; login, `/auth/me` and `/internal/v1/videos/pending` all work |
| admin name, valid session cookie but **no** certificate | 400, so a stolen session or password alone is not enough |
| request by IP address (no known name) | refused during the handshake (`ssl_reject_handshake`) |
| handshake as the public name, `Host: admin...`, no certificate | 421, **also with the two guard checks removed**, so the version does it by itself |
| a path not on the admin allowlist (`/api/v1/feed`) | the admin page, not the API |

The session cookie is `HttpOnly`, `Secure` and host-only, so the public name never receives the admin's.

Two corrections to what was assumed beforehand: with `ssl_verify_client on` nginx completes the TLS
handshake and then answers 400 (it does not abort the handshake), and the SNI/Host mismatch did not
reproduce on this version.

Not tested: a browser with an imported client certificate, HTTP/2, an L7 load balancer in front (it would terminate TLS, so nginx would never see the certificate), and
older nginx versions. The generated keys are unprotected files; a real staff CA belongs offline, with
short-lived client certificates, a revocation process and MFA in the application.

### Revoking a client certificate

`mtls/revoke-dev-cert.sh alice` revokes a certificate and rewrites the list nginx reads (`ssl_crl`).
Tested with two staff certificates (`alice`, `bob`):

| Step | alice | bob |
|---|---|---|
| before any revocation | 200 | 200 |
| alice revoked, list rewritten, nginx **not** reloaded | **200** | 200 |
| after `docker exec sv-nginx-mtls nginx -s reload` | 400 | 200 |
| the list is regenerated valid for 5 s, then the time passes (no reload) | - | **400** |
| a fresh 7-day list and a reload | 400 | 200 |

- **A revocation is not immediate.** nginx reads the list when it starts or reloads, so a lost laptop
  stays able to connect until someone regenerates the list and reloads.
- **A revocation is selective:** bob was unaffected.
- **An expired list locks everyone out.** Once the list passes its next-update time nginx refuses every
  client certificate, the valid ones too, and no reload is needed for that to happen. So the list must be
  regenerated on a schedule well inside its validity (here 7 days) and the age of the file watched
  (an alert on the next-update time, or on the age of `staff-ca.crl`), the same way a TLS certificate's
  expiry is.
- The revocation list is per CA: a client certificate must come from the CA the list belongs to
  (`alice` and `bob` do, `mallory` does not and is refused regardless).
- Not tested: OCSP (nginx does not do it for client certificates), a CRL served by URL, or a large list.
