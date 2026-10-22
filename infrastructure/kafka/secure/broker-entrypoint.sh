#!/usr/bin/env bash
# Replaces the image's own entrypoint: its env-var-to-properties wrapper cannot pass --add-scram
# to the storage format step, and SCRAM users have to exist before the first authenticated
# connection (including the broker's own, to the controller).
set -euo pipefail

: "${CLUSTER_ID:?}" "${KAFKA_ADMIN_PASSWORD:?}" "${KAFKA_BACKEND_PASSWORD:?}" \
  "${KAFKA_WORKER_PASSWORD:?}" "${KAFKA_UI_PASSWORD:?}"

PROPS=/tmp/server.properties
sed "s|@ADMIN_PASSWORD@|${KAFKA_ADMIN_PASSWORD}|g" /etc/kafka/secure/server.properties.tmpl > "$PROPS"

# The same credentials, as a client config for the healthcheck and for kafka-init's CLI calls.
cat > /tmp/admin.properties <<CFG
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="${KAFKA_ADMIN_PASSWORD}";
CFG

scram() { echo "SCRAM-SHA-512=[name=$1,password=$2]"; }
/opt/kafka/bin/kafka-storage.sh format --ignore-formatted \
  --cluster-id "$CLUSTER_ID" --config "$PROPS" \
  --add-scram "$(scram admin "$KAFKA_ADMIN_PASSWORD")" \
  --add-scram "$(scram backend "$KAFKA_BACKEND_PASSWORD")" \
  --add-scram "$(scram media-worker "$KAFKA_WORKER_PASSWORD")" \
  --add-scram "$(scram kafka-ui "$KAFKA_UI_PASSWORD")"

exec /opt/kafka/bin/kafka-server-start.sh "$PROPS"
