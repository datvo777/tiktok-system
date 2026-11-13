# The FFmpeg media worker. Build from the repository root:
#   docker build -f media-worker/Dockerfile -t sv-media-worker .
# or through infrastructure/docker-compose.yml (profile "worker").

# ---- build ------------------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# The whole tree, not just media-worker: the root pom lists every module, and Maven refuses a
# reactor with a module directory missing. -am builds only what the worker depends on.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl media-worker -am -DskipTests package \
 && cp media-worker/target/media-worker.jar /media-worker.jar

# ---- runtime ----------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

# ffmpeg and ffprobe are looked up on PATH by name (WorkerProperties). tini is PID 1 so that signals
# reach the JVM and the ffmpeg children it spawns are reaped. curl is for the healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg tini curl \
 && rm -rf /var/lib/apt/lists/* \
 # Fail the build, not the first job, if the tools are missing or a base-image change moved the
 # series: the ladder and the segment alignment tests were written against 6.1.
 && ffmpeg -version | head -1 | grep -q '^ffmpeg version 6\.1' \
 && ffprobe -version | head -1 | grep -q '^ffprobe version 6\.1' \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin worker

COPY --from=build /media-worker.jar /app/media-worker.jar

USER 10001
EXPOSE 8090
# The heap is only part of what a job uses: each ffmpeg is a separate process outside it, so the
# heap is kept to a minority of the container's memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=35 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8090/actuator/health/liveness || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/app/media-worker.jar"]
