#!/usr/bin/env bash
# Generates everything docker-compose.secure.yml needs and that must NOT be committed:
# a throwaway CA, the broker's TLS key and certificate, and one random password per principal.
# Output goes to ./generated (gitignored). Safe to rerun: existing files are kept unless
# --force is given, because changing a password means recreating the broker (SCRAM users are
# written when its storage is formatted).
#
# Dev only. Production gets its certificates from a real PKI and its secrets from a secret manager.
set -euo pipefail
cd "$(dirname "$0")"
OUT=generated
FORCE=${1:-}

[ "$FORCE" = "--force" ] && rm -rf "$OUT"
mkdir -p "$OUT"
cd "$OUT"

if [ ! -f ca.crt ]; then
  openssl req -x509 -newkey rsa:4096 -nodes -sha256 -days 825 \
    -subj "/CN=short-video-local-kafka-ca" -keyout ca.key -out ca.crt 2>/dev/null

  openssl req -newkey rsa:2048 -nodes -sha256 -subj "/CN=kafka" -keyout broker.key -out broker.csr 2>/dev/null
  # SAN covers every name a client uses: the host listener (localhost), the compose network (kafka).
  printf 'subjectAltName=DNS:localhost,DNS:kafka,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > san.ext
  openssl x509 -req -in broker.csr -CA ca.crt -CAkey ca.key -CAcreateserial -sha256 -days 825 \
    -extfile san.ext -out broker.crt 2>/dev/null

  # Kafka's PEM keystore: PKCS#8 private key followed by the certificate chain, one file.
  openssl pkcs8 -topk8 -nocrypt -in broker.key -out broker.pk8
  cat broker.pk8 broker.crt > broker.pem
  rm -f broker.csr broker.pk8 san.ext ca.srl
  chmod 600 broker.key ca.key broker.pem
  chmod 644 ca.crt broker.crt
  # The broker container runs as a different uid and only needs to read the one file it serves.
  chmod 644 broker.pem
fi

if [ ! -f secrets.env ]; then
  pw() { openssl rand -hex 24; }
  umask 077
  cat > secrets.env <<ENV
KAFKA_ADMIN_PASSWORD=$(pw)
KAFKA_BACKEND_PASSWORD=$(pw)
KAFKA_WORKER_PASSWORD=$(pw)
KAFKA_UI_PASSWORD=$(pw)
ENV
  chmod 644 secrets.env
fi

# Client settings for the two Java processes, in the form Spring Boot reads from the environment.
# Meant to be sourced by a shell (set -a; . file; set +a), so the JAAS value is single-quoted.
# Separate files because they are separate principals: the worker must not hold the backend's
# credentials (that is the point of the ACLs).
set -a; . ./secrets.env; set +a
HERE=$(pwd)
write_client_env() { # <file> <principal> <password>
  cat > "$1" <<ENV
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:29092
SPRING_KAFKA_SECURITY_PROTOCOL=SASL_SSL
SPRING_KAFKA_PROPERTIES_SASL_MECHANISM=SCRAM-SHA-512
SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG='org.apache.kafka.common.security.scram.ScramLoginModule required username="$2" password="$3";'
SPRING_KAFKA_SSL_TRUST_STORE_TYPE=PEM
SPRING_KAFKA_SSL_TRUST_STORE_LOCATION=file:$HERE/ca.crt
ENV
  chmod 600 "$1"
}
write_client_env client-backend.env backend "$KAFKA_BACKEND_PASSWORD"
write_client_env client-worker.env media-worker "$KAFKA_WORKER_PASSWORD"

echo "Wrote $(pwd)"
echo "  broker: ca.crt broker.pem secrets.env"
echo "  clients: client-backend.env client-worker.env"
