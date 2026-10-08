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
  echo "backend did not stop" >&2; return 1
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
    # Same flags for every run. Deliberately no -XX:TieredStopAtLevel=1.
    nohup java -Xms2g -Xmx4g -XX:+UseG1GC -jar backend/app/target/app.jar > "$OUT/backend-$mode.log" 2>&1 &
    echo $! > "$OUT/backend.pid"
    for _ in $(seq 1 90); do
      [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health/liveness)" = 200 ] && { echo "backend up ($mode), pid $(cat "$OUT/backend.pid")"; exit 0; }
      sleep 2
    done
    echo "backend did not come up" >&2; exit 1 ;;
  *) echo "usage: $0 start [polling|sse] | stop" >&2; exit 2 ;;
esac
