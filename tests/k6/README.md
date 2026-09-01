# Load tests (Milestone 8)

Requires the local stack up (`docker compose ... up -d`), the backend running
on `:8080`, and an admin account (`ADMIN_EMAIL`/`ADMIN_PASSWORD`) already
elevated via `UPDATE account.account SET roles = 'USER,ADMIN' WHERE email = ...`.

```bash
brew install k6

# Gateway concurrent range-request load (brief section 20: "gateway memory
# stays flat", "revocation latency distributions measured under concurrent load").
# Watch JVM heap in Grafana (or actuator/prometheus) before/during/after.
BASE_URL=http://localhost:8080 VIDEO_ID=<a-published-video-id> k6 run tests/k6/media-gateway-load.js

# Concurrent upload+poll cycles (brief section 20: "polling load from
# concurrent uploads stays within the local target").
BASE_URL=http://localhost:8080 k6 run tests/k6/upload-poll-load.js
```

`media-gateway-load.js` defaults to a fixture video id and its known segment
size; point `VIDEO_ID` at a real published video's `processingVersion=1`
segment if that default no longer exists, and update `SEGMENT_BYTES` to match
(`mc ls -r local/short-video/processed/<videoId>/1/`).

`upload-poll-load.js` uses `tests/k6/fixtures/sample.mp4`, a small synthetic
clip (`ffmpeg -f lavfi -i testsrc=duration=2 ...`) — small on purpose, since
each VU iteration triggers a real transcode.
