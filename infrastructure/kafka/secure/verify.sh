#!/usr/bin/env bash
# Proves the secure stack enforces what the ADR says it does, from the broker's side: anonymous
# and wrong-password clients are refused, and each principal can do exactly its own job and
# nothing else. Runs the Kafka CLI inside the broker container against the HOST listener, so
# the TLS + SCRAM path under test is the one the Java processes use.
#
#   infrastructure/kafka/secure/verify.sh                       # the default stack's names
#   KAFKA_CONTAINER=svt-kafka KAFKA_PORT=39092 .../verify.sh     # another stack
set -uo pipefail
cd "$(dirname "$0")"
C=${KAFKA_CONTAINER:-sv-kafka}
PORT=${KAFKA_PORT:-29092}
BS=localhost:$PORT
BIN=/opt/kafka/bin
set -a; . generated/secrets.env; set +a

docker exec -i "$C" sh -c 'cat > /tmp/ca.crt' < generated/ca.crt
mkprops() { # <name> <user> <password>
  docker exec -i "$C" sh -c "cat > /tmp/$1.properties" <<CFG
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
ssl.truststore.type=PEM
ssl.truststore.location=/tmp/ca.crt
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="$2" password="$3";
CFG
}
mkprops admin admin "$KAFKA_ADMIN_PASSWORD"
mkprops backend backend "$KAFKA_BACKEND_PASSWORD"
mkprops worker media-worker "$KAFKA_WORKER_PASSWORD"
mkprops ui kafka-ui "$KAFKA_UI_PASSWORD"
mkprops wrongpw backend "not-the-password"

pass=0; fail=0
DENIED='AuthorizationException|Authentication failed|SaslAuthenticationException|not authorized'
check() { # <description> <ok|denied> <output> <exit code>
  # denied = the broker said so, in words. A timeout or a dropped connection proves nothing:
  # it is also what a misconfigured client looks like, and would pass this test for free.
  # ok = it worked: exit 0, no authorization error, and (for a read) a message actually came back.
  local desc=$1 expect=$2 out=$3 rc=$4 good=0
  if [ "$expect" = denied ]; then
    echo "$out" | grep -qE "$DENIED" && good=1
  else
    [ "$rc" = 0 ] && ! echo "$out" | grep -qE "$DENIED|Exception" && good=1
  fi
  if [ "$good" = 1 ]; then pass=$((pass+1)); echo "  ok    $desc"
  else fail=$((fail+1)); echo "  FAIL  $desc (expected $expect, exit $rc)"; echo "$out" | grep -v '^\s*at ' | tail -3 | sed 's/^/        /'; fi
}
produce() { # <props> <topic>
  out=$(echo "probe-$$" | docker exec -i "$C" timeout 40 $BIN/kafka-console-producer.sh --bootstrap-server $BS \
    --producer.config "/tmp/$1.properties" --topic "$2" 2>&1); rc=$?
}
consume() { # <props> <topic> <group> — expects a message to be waiting there
  out=$(docker exec "$C" timeout 40 $BIN/kafka-console-consumer.sh --bootstrap-server $BS \
    --consumer.config "/tmp/$1.properties" --topic "$2" --group "$3" --from-beginning --max-messages 1 \
    --timeout-ms 15000 2>&1); rc=$?
  echo "$out" | grep -q 'Processed a total of 1 messages' || rc=1
}

# Something for every read test to find, put there by the super user.
for t in video.events.v1 media.jobs.v1 notification.events.v1 video.events.v1.DLT; do
  produce admin "$t"; [ $rc = 0 ] || { echo "setup failed: admin cannot write $t"; echo "$out" | tail -3; exit 2; }
done

echo "no valid identity"
out=$(docker exec "$C" timeout 20 $BIN/kafka-topics.sh --bootstrap-server $BS --list 2>&1); rc=$?
# No topic names may come back, whatever else happens.
[ $rc -ne 0 ] && ! echo "$out" | grep -q 'video.events' && { pass=$((pass+1)); echo "  ok    anonymous (PLAINTEXT) client is refused"; } || { fail=$((fail+1)); echo "  FAIL  anonymous client got in"; }
out=$(docker exec "$C" timeout 20 $BIN/kafka-topics.sh --bootstrap-server $BS --command-config /tmp/wrongpw.properties --list 2>&1); rc=$?
check "wrong password is refused" denied "$out" $rc

echo "backend"
produce backend video.events.v1;         check "writes video.events.v1 (outbox relay)" ok "$out" $rc
produce backend media.jobs.v1;           check "writes media.jobs.v1 (transcode commands)" ok "$out" $rc
produce backend media.results.v1;        check "cannot write media.results.v1 (worker's output)" denied "$out" $rc
produce backend media.jobs.v1.DLT;       check "cannot write media.jobs.v1.DLT (worker's dead letters)" denied "$out" $rc
consume backend video.events.v1 eligibility-video-projector; check "reads video.events.v1 as a listed group" ok "$out" $rc
consume backend video.events.v1 rogue-group;                 check "cannot join an unlisted group" denied "$out" $rc
consume backend notification.events.v1 "realtime-$(date +%s)"; check "reads notification.events.v1 as realtime-<uuid>" ok "$out" $rc
consume backend video.events.v1.DLT dlq-alert-sink;          check "reads a DLT as dlq-alert-sink" ok "$out" $rc
out=$(docker exec "$C" $BIN/kafka-cluster.sh cluster-id --bootstrap-server $BS --config /tmp/backend.properties 2>&1); rc=$?
check "describes the cluster (KafkaHealthIndicator)" ok "$out" $rc
out=$(docker exec "$C" timeout 25 $BIN/kafka-topics.sh --bootstrap-server $BS --command-config /tmp/backend.properties \
  --create --topic backend-must-not-create --partitions 1 --replication-factor 1 2>&1); rc=$?
check "cannot create topics" denied "$out" $rc

echo "media-worker"
produce worker media.results.v1;         check "writes media.results.v1" ok "$out" $rc
produce worker video.events.v1;          check "cannot write video.events.v1" denied "$out" $rc
consume worker media.jobs.v1 media-worker; check "reads media.jobs.v1 as media-worker" ok "$out" $rc
consume worker video.events.v1 media-worker; check "cannot read video.events.v1" denied "$out" $rc

echo "kafka-ui"
# Kafka UI reads with no group at all (manual partition assignment), so it needs no group ACL.
# Keyless probe messages land on one arbitrary partition, hence the loop.
rc=1
for part in 0 1 2; do
  out=$(docker exec "$C" timeout 40 $BIN/kafka-console-consumer.sh --bootstrap-server $BS \
    --consumer.config /tmp/ui.properties --topic video.events.v1 --partition $part --offset 0 --max-messages 1 \
    --timeout-ms 8000 2>&1)
  echo "$out" | grep -q 'Processed a total of 1 messages' && { rc=0; break; }
  echo "$out" | grep -qE "$DENIED" && break
done
check "reads messages without a group" ok "$out" $rc
produce ui video.events.v1;              check "cannot write anything" denied "$out" $rc

echo
echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
