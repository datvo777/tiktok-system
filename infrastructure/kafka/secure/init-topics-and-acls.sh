#!/bin/sh
# Creates the topics, then grants each principal exactly what it needs. Runs as `admin`
# (a super user) from the kafka-init container. Idempotent: --if-not-exists, and re-adding an
# identical ACL is a no-op.
set -eu

BS=kafka:9092
CFG=/tmp/admin.properties
BIN=/opt/kafka/bin
SECURE=/etc/kafka/secure

cat > "$CFG" <<CFGEOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="${KAFKA_ADMIN_PASSWORD}";
CFGEOF

DOMAIN="video.events.v1 account.events.v1 social.events.v1 media.jobs.v1 media.results.v1 notification.events.v1"

for t in $DOMAIN; do
  $BIN/kafka-topics.sh --bootstrap-server $BS --command-config $CFG \
    --create --if-not-exists --topic "$t" --partitions 3 --replication-factor 1
done
for t in $DOMAIN; do
  $BIN/kafka-topics.sh --bootstrap-server $BS --command-config $CFG \
    --create --if-not-exists --topic "$t.DLT" --partitions 1 --replication-factor 1 \
    --config retention.ms=1209600000 --config cleanup.policy=delete
done

acl() { $BIN/kafka-acls.sh --bootstrap-server $BS --command-config $CFG --add "$@" >/dev/null; }
topic() { # <principal> <topic> <operation>...
  p=$1; t=$2; shift 2
  for op in "$@"; do acl --allow-principal "User:$p" --operation "$op" --topic "$t"; done
}

# --- backend --------------------------------------------------------------------------------
# Writes: the outbox relay (TopicResolver) and the dead-letter recoverer.
for t in video.events.v1 account.events.v1 social.events.v1 notification.events.v1 media.jobs.v1; do
  topic backend "$t" Write Describe
done
# One DLT per topic a backend listener consumes. media.jobs.v1.DLT is the worker's, not ours.
for t in video.events.v1 account.events.v1 social.events.v1 media.results.v1 notification.events.v1; do
  topic backend "$t.DLT" Write Describe
done
# Reads: every domain topic a listener consumes, and all DLTs. DeadLetterAlertListener
# subscribes by pattern ".*\.DLT"; ACLs cannot express a suffix, so each DLT is a literal.
for t in video.events.v1 account.events.v1 social.events.v1 media.results.v1 notification.events.v1; do
  topic backend "$t" Read Describe
done
for t in $DOMAIN; do
  topic backend "$t.DLT" Read Describe
done
while read -r g; do
  case "$g" in ''|'#'*) continue ;; esac
  acl --allow-principal User:backend --operation Read --group "$g"
done < "$SECURE/acl-groups.txt"
acl --allow-principal User:backend --operation Read --group realtime- --resource-pattern-type prefixed
# KafkaHealthIndicator's AdminClient.describeCluster.
acl --allow-principal User:backend --operation Describe --cluster

# --- media-worker ---------------------------------------------------------------------------
topic media-worker media.jobs.v1 Read Describe
topic media-worker media.results.v1 Write Describe
topic media-worker media.jobs.v1.DLT Write Describe
acl --allow-principal User:media-worker --operation Read --group media-worker

# --- kafka-ui: look, don't touch ------------------------------------------------------------
acl --allow-principal User:kafka-ui --operation Read --operation Describe --operation DescribeConfigs --topic '*'
acl --allow-principal User:kafka-ui --operation Describe --group '*'
acl --allow-principal User:kafka-ui --operation Describe --operation DescribeConfigs --cluster

echo "topics and ACLs ready"
