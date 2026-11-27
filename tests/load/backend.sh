#!/usr/bin/env bash
# Starts/stops the backend for the SSE-vs-polling benchmark, from the built jar (not spring-boot:run,
# whose dev JVM flags include -XX:TieredStopAtLevel=1) against a scratch database.
#
#   tests/load/backend.sh start [polling|sse]    # sse enables shortvideo.realtime
#   tests/load/backend.sh stop
#
# Requires: the compose stack up, `mvn package -DskipTests` run, and the scratch database:
#   docker exec sv-postgres psql -U shortvideo -d postgres -c "CREATE DATABASE short_video_bench" \
#     -c "GRANT CONNECT, CREATE ON DATABASE short_video_bench TO short_video_app"
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="${BENCH_OUT:-/tmp/sv-bench}"
mkdir -p "$OUT"

stop() {
  local pid
  for pid in $(lsof -ti :8080 -sTCP:LISTEN 2>/dev/null || true); do kill "$pid" 2>/dev/null || true; done
  for _ in $(seq 1 30); do lsof -ti :8080 -sTCP:LISTEN >/dev/null 2>&1 || return 0; sleep 1; done
  # A wedged JVM does not honour SIGTERM; a benchmark that goes on to talk to it measures nothing.
  echo "backend did not stop on SIGTERM; killing" >&2
  for pid in $(lsof -ti :8080 -sTCP:LISTEN 2>/dev/null || true); do kill -9 "$pid" 2>/dev/null || true; done
  sleep 2
}

case "${1:-}" in
  stop) stop ;;
  start)
    mode="${2:-polling}"
    stop || true
    set -a; . ./.env; set +a
    export POSTGRES_DB=short_video_bench
    export SHORTVIDEO_LOGIN_RATE_LIMIT_ENABLED=false SHORTVIDEO_REGISTER_RATE_LIMIT_ENABLED=false
    # Cookies are minted once, up front; they must outlive the whole session (nothing here refreshes).
    export JWT_TTL_SECONDS=28800
    export REALTIME_ENABLED=$([ "$mode" = sse ] && echo true || echo false)
    export REALTIME_MAX_CONNECTIONS=20000
    # The seeder creates ~800 upload drafts in a minute; the global brake (600/min) would refuse them.
    export SHORTVIDEO_UPLOAD_CREATE_RATE_LIMIT_GLOBAL_MAX_PER_WINDOW=0
    # Tomcat's default of 8192 connections would refuse the second half of 8000 SSE users, who hold two
    # connections each over HTTP/1.1 (the stream, and the keep-alive one for REST). Lifted so the run
    # measures cost; the limit itself is reported as a finding.
    export TOMCAT_MAX_CONNECTIONS=40000 TOMCAT_ACCEPT_COUNT=1000
    # GATE=off removes the open-rate and concurrent-open limits, to measure what they are worth.
    if [ "${GATE:-on}" = off ]; then
      export SHORTVIDEO_REALTIME_MAX_OPENS_PER_SECOND=0 SHORTVIDEO_REALTIME_MAX_CONCURRENT_OPENS=1000
    fi
    # Same flags for every run. Deliberately no -XX:TieredStopAtLevel=1.
    # Run from a private copy. A jar replaced underneath a running JVM (an IDE build, another `mvn package`)
    # makes it fail on the next class it loads lazily, with NoClassDefFoundError from unrelated places;
    # that corrupted one measurement run before this was added.
    [ -f "$OUT/app.jar" ] || cp backend/app/target/app.jar "$OUT/app.jar"
    # -XX:-MaxFDLimit: on macOS the JVM otherwise caps its own descriptor limit at 10240, below what
    # thousands of connections need (two descriptors per SSE user).
    nohup java -Xms2g -Xmx4g -XX:+UseG1GC -XX:-MaxFDLimit -jar "$OUT/app.jar" > "$OUT/backend-$mode.log" 2>&1 &
    echo $! > "$OUT/backend.pid"
    for _ in $(seq 1 90); do
      [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health/liveness)" = 200 ] && { echo "backend up ($mode), pid $(cat "$OUT/backend.pid")"; exit 0; }
      sleep 2
    done
    echo "backend did not come up" >&2; exit 1 ;;
  *) echo "usage: $0 start [polling|sse] | stop" >&2; exit 2 ;;
esac
