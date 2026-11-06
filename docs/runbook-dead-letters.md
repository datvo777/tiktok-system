# Runbook: a record reached a dead-letter topic

The `KafkaRecordDeadLettered` alert (critical) fires when a consumer gave up on a record: it failed its
retries, or could never have succeeded (a malformed payload skips the retries). The record is now in
`<topic>.DLT`, the consumer has moved past it, and **nothing replays it automatically**. This page is what to do.

| DLT | Written by | A record here means |
|---|---|---|
| `media.jobs.v1.DLT` | media-worker | a transcode command the worker could not decode or handle |
| `media.results.v1.DLT` | backend (video) | a transcode result the Video module could not apply |
| `video.events.v1.DLT`, `account.events.v1.DLT`, `social.events.v1.DLT`, `notification.events.v1.DLT` | backend | an event a backend listener could not apply |

Retries before a record is dead-lettered: five (1s doubling to 30s) for an ordinary failure, none for a
payload that does not parse, and nine (about 2.5 minutes) when the object store could not be asked.
DLT records are kept 14 days.

## 1. Look at it

Kafka UI (<http://localhost:8081>) shows the topic, or from a shell:

```bash
docker exec sv-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic media.results.v1.DLT --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true --property print.offset=true \
  --property print.headers=true
```

The `TimeoutException` the tool prints when it stops waiting is normal. The key is the video id. The
headers carry the original topic, partition and offset and the exception class and message
(`kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`): that is the cause, so read it before anything else.

## 2. Decide

| Cause | Replay? | What to do |
|---|---|---|
| The payload is malformed (JSON error, unknown shape, a `videoId` that is not a UUID) | **No**, it will fail again | Find who produced it and fix that. For a transcode command the video stays `TRANSCODING`; the watchdog fails it after 60 minutes (`FAILED`/`TRANSIENT`), then an admin can `reprocess` it. `reprocess` does nothing while a video is still `TRANSCODING`, so there is no shortcut. |
| A dependency was down (MinIO, Postgres) and has recovered | **Yes** | Replay the record (step 3). |
| A bug in a listener, now fixed and deployed | **Yes** | Replay the record. |

Replay only while it can still matter. A transcode result applies only if its video is still
`TRANSCODING` at that `processingVersion`; once the watchdog has failed the video (60 minutes after the
command was queued) the replayed result is ignored and its output purged, so `reprocess` the video instead.
Check the video's `processingState` first.

## 3. Replay one record

Replaying is safe: every consumer deduplicates through the inbox by event id, a transcode job id is
deterministic, and a result for an old `processingVersion` or an already-decided video is ignored. A
record that was in fact applied is applied once.

Take the partition and offset from step 1 and republish that one record, with its key, to the original topic:

```bash
docker exec sv-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic media.results.v1.DLT --partition 0 --offset 3 --max-messages 1 --timeout-ms 5000 \
    --property print.key=true 2>/dev/null \
  | docker exec -i sv-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
    --topic media.results.v1 --property parse.key=true
```

Replay one record at a time, never the whole topic. The tab between key and value is the default separator
for both tools. Against the authenticated stack add `--command-config /tmp/admin.properties` to each tool
(the broker container has that file; see `docker-compose.secure.yml`).

## 4. Check it worked

- The video's `processingState` moved (`READY`, or retrying).
- `kafka_consumer_dead_lettered_total` stopped rising. If the record is dead-lettered again the cause is
  not fixed; do not loop.
- `video_transcode_result_ignored_total` by `reason` says how many results were dropped and why
  (`stale_version`, `already_terminal`, `removed`, `unknown_video`).

## If there is no DLT topic

The DLT topics are created by `kafka-init`. A local stack created before they were added has none, and a
dead-lettered record then has nowhere to go. Re-run it (idempotent):

```bash
docker compose --env-file .env -f infrastructure/docker-compose.yml up kafka-init
```
