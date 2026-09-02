# Short-Video Platform — Local macOS Build

Milestone 1 of the implementation brief: foundation, account module, session
transport, and the media-gateway route skeleton. Upload, transcoding and playback
are Milestone 2.

The authoritative spec is `short-video-platform-final-implementation-brief-FINAL.md`
in this folder. Where this README and the brief disagree, the brief wins.

---

## Status of this scaffold

**Read this before your first build.** This code was written in a sandbox with no
access to Maven Central, so **the Java side has never been compiled**. The web
client *was* installed, typechecked and built successfully. Expect to fix a small
number of dependency or API mismatches on first `mvn install` — most likely
candidates are the pinned versions in the root `pom.xml` and the jjwt
0.12.x builder API in `JwtService`.

Docker image tags in `infrastructure/docker-compose.yml` are pinned but also
unverified. On first `compose pull`, confirm each resolves for **linux/arm64**
(Apple Silicon) and replace the tag with a digest once it works.

---

## Prerequisites

```bash
brew install openjdk@21 maven ffmpeg node jq k6
brew install --cask docker
```

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

Add those two lines to `~/.zshrc`, then `source ~/.zshrc`. Confirm with
`java -version` — the build is pinned to Java 21 (`maven.compiler.release` in
the root `pom.xml`) and will refuse to build on anything else.

---

## First run

```bash
cp .env.example .env

# 1. Dependencies
docker compose --env-file .env -f infrastructure/docker-compose.yml up -d
docker compose --env-file .env -f infrastructure/docker-compose.yml ps

# 2. Backend (runs on the host, not in Compose)
mvn -pl backend/app -am spring-boot:run

# 3. Media worker, in a second terminal
mvn -pl media-worker -am spring-boot:run

# 4. Web client, in a third
cd web && npm install && npm run dev
```

Then open <http://localhost:5173>. Register, log in, and press **Probe /media**.

| Service | URL |
| --- | --- |
| Web client | http://localhost:5173 |
| Backend | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Kafka UI | http://localhost:8081 |
| MinIO console | http://localhost:9001 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |

---

## Verifying Milestone 1

The brief's acceptance criteria, and how to check each one.

**Dependencies healthy.** `docker compose ... ps` shows every service healthy and
`sv-minio-init` exited 0. Rerunning `up -d` is a no-op — `mc mb --ignore-existing`.

**Both processes reach Kafka through the HOST listener.**

```bash
curl -s localhost:8080/actuator/health/readiness | jq '.components.kafka'
```

Kafka advertises two listeners: containers use `kafka:9092`, host processes use
`localhost:29092`. If this is DOWN while Kafka UI works, you are pointing the
backend at the internal listener.

**The bucket is private.** This is the one to actually run, because a public
bucket silently voids the whole authorization model (Rule 18):

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  http://localhost:9000/short-video/processed/probe.m3u8
```

Expect `403`. A `404` means the bucket is anonymously listable and something
added a public policy — `infrastructure/minio/init.sh` asserts against this at
startup, so check what changed there.

**Flyway against an empty database.** Drop the volume and restart:

```bash
docker compose --env-file .env -f infrastructure/docker-compose.yml down -v
```

**Registration stores a BCrypt hash; login sets an HttpOnly cookie.**

```bash
curl -i -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"creator@example.com","password":"correct-horse-battery"}'
```

Look for `Set-Cookie: sv_session=...; HttpOnly; SameSite=Lax; Path=/`.

**Media accepts the cookie and refuses the header.** This is Rule 17 and the
easiest thing to get subtly wrong:

```bash
TOKEN=... # from the login response body

# Bearer header: 401. hls.js cannot send headers, so this path must not exist.
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  localhost:8080/media/videos/3f2504e0-4f89-41d3-9a0c-0305e82c3301/1/master.m3u8

# Session cookie: 501 — authenticated, path validated, delivery is Milestone 2.
curl -s -o /dev/null -w '%{http_code}\n' \
  --cookie "sv_session=$TOKEN" \
  localhost:8080/media/videos/3f2504e0-4f89-41d3-9a0c-0305e82c3301/1/master.m3u8
```

**Tests.**

```bash
mvn test        # unit tests, no Docker needed
mvn install     # includes the Testcontainers integration tests
```

The integration tests start their own PostgreSQL through Testcontainers; they do
not touch your Compose stack.

**If Testcontainers cannot reach Docker.** Testcontainers needs the Docker
*API*, which is not always available even where the `docker` CLI works — a
misbehaving Docker Desktop can answer the CLI while erroring on the daemon
socket, and CI often supplies a database as a service container with no Docker
socket inside the job. The PostgreSQL-only suites accept an existing database
instead:

```bash
TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/short_video_test \
TEST_POSTGRES_USER=short_video_app TEST_POSTGRES_PASSWORD=short_video_app \
  mvn -pl backend/app test -Dtest='AccountFlowIT,AuthorizationIT,SessionLifecycleIT'
```

Point that at a scratch database, never a working one — Flyway migrates whatever
it is given and the tests write freely:

```bash
docker exec sv-postgres psql -U postgres -c 'CREATE DATABASE short_video_test OWNER short_video_app;'
```

`ModerationPublicationFlowIT`, `ResilienceIT` and `UploadTranscodeFlowIT` also
need Kafka and MinIO, and stay on Testcontainers — disposable infrastructure is
the point for those.

---

## Layout

```text
.
├── backend/
│   ├── app/                  Spring Boot entry point, security, health, Kafka/MinIO config
│   ├── modules/
│   │   ├── account/          Registration, login, suspension. Owns the account schema.
│   │   └── playback/         Media gateway route + path validation
│   └── shared/
│       ├── events/           Versioned event envelope, topic and type constants
│       ├── outbox/           Transactional outbox, claim/lease relay, platform schema
│       ├── inbox/            Durable consumer dedupe
│       ├── security/         JWT issue/parse, session cookies, auth filter
│       └── observability/    Request and correlation IDs
├── media-worker/             Separate FFmpeg process. No database, by design.
├── web/                      React + Vite client (single-origin proxy)
├── contracts/openapi/        API contracts
├── infrastructure/           Compose stack, MinIO and Postgres init
└── docs/
```

---

## Design decisions worth knowing before you edit

**The media gateway returns 501, on purpose.** The route, cookie authentication
and path validation are real; byte delivery is not. Until the authority order in
brief section 8 exists — Redis deny, then durable PostgreSQL revocation, then a
durable eligibility allow — there is no safe way to serve a byte. An endpoint
that streamed media before those checks existed would be precisely the failure
the design prevents.

**Media authenticates from cookies only.** `JwtAuthenticationFilter` ignores
`Authorization` on `/media` even when present. That is deliberate: accepting a
header there would let tests pass through a path the browser can never use.

**The Vite proxy is load-bearing.** `SameSite=Lax` cookies are not sent on
cross-origin subresource requests, and an HLS segment request is exactly that.
The proxy makes the browser see one origin. Permissive CORS with credentials is
not an acceptable substitute.

**The media worker has no database and no outbox.** It cannot join a transaction,
so it emits results as commands (`media.results.v1`), and the Video module
applies them through its inbox and owns the `READY` transition. That is Rule 16,
and it is why the worker is the only Kafka producer here without an outbox.

**`ProcessRunner` drains both pipes.** FFmpeg writes progress to stderr
continuously; an undrained pipe fills, the child blocks forever, and no
exit-based timeout ever fires. `ProcessRunnerTest` floods 100k stderr lines to
keep that regression out.

**`mc anonymous set` must never appear in `infrastructure/minio/init.sh`.** The
file says so at the top, and the script asserts the bucket is private on every
start.

---

## Not implemented yet (and shouldn't be)

Moderation, publication, feed, social, appeals, search, notifications — these are
Milestones 3 through 7. `notification.events.v1` is deliberately not created
until its consumer exists (Rule 1). OpenSearch is not in the Compose stack until
Milestone 7.

Idempotency keys are specified in brief section 12.2 but not enforced yet:
Milestone 1 has no mutation that needs them. They land with upload completion in
Milestone 2.

---

## Milestone 2, in order

1. Upload module and presigned MinIO uploads; video draft created in the same
   transaction as the upload session, returning `uploadId` and `videoId`.
2. `processingVersion` assignment and the `media.jobs.v1` command.
3. Real transcoding in the worker on top of `ProcessRunner`, writing to
   `processing-temp/{jobId}/` and promoting only after validation.
4. Video module consuming `media.results.v1` through the inbox; `READY` + durability.
5. Eligibility projector rows for video and account.
6. Owner-preview playback sessions and range-aware bounded streaming, replacing
   the 501.
