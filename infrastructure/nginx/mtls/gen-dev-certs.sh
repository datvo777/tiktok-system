#!/usr/bin/env bash
# Throwaway certificates for trying the mTLS admin host locally (infrastructure/nginx/mtls.conf).
# Writes to ./generated (git-ignored). NOT for production: the keys sit on disk unprotected and the
# CA is trusted by nothing but this experiment. A real staff CA belongs offline, with short-lived
# client certificates and a revocation process (see README.md).
set -euo pipefail
cd "$(dirname "$0")"
OUT=generated
mkdir -p "$OUT"
cd "$OUT"
DAYS=30

# Staff CA: signs the client certificates nginx accepts on the admin host.
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days "$DAYS" \
  -subj "/CN=dev staff CA" -keyout staff-ca.key -out staff-ca.pem \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

# A second CA nginx does not trust, to show a certificate from elsewhere is refused.
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days "$DAYS" \
  -subj "/CN=untrusted CA" -keyout rogue-ca.key -out rogue-ca.pem \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign" 2>/dev/null

# Server certificate for both hostnames, signed by its own CA (the "public" CA of this experiment).
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days "$DAYS" \
  -subj "/CN=tksystem.localhost" -keyout server.key -out server.pem \
  -addext "subjectAltName=DNS:tksystem.localhost,DNS:admin.tksystem.localhost" \
  -addext "extendedKeyUsage=serverAuth" 2>/dev/null

# The staff CA keeps a database of what it issued, which is what lets it revoke (revoke-dev-cert.sh).
mkdir -p newcerts
: > index.txt
echo 1000 > serial
echo 1000 > crlnumber
cat > ca.cnf <<'CNF'
[ ca ]
default_ca = CA_default
[ CA_default ]
dir = .
database = ./index.txt
new_certs_dir = ./newcerts
serial = ./serial
crlnumber = ./crlnumber
default_md = sha256
default_days = 30
default_crl_days = 7
policy = policy_any
x509_extensions = client_ext
unique_subject = no
[ policy_any ]
commonName = supplied
[ client_ext ]
basicConstraints = CA:FALSE
extendedKeyUsage = clientAuth
CNF

staff_client() { # name: issued by the staff CA, so it can be revoked
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj "/CN=$1" -keyout "$1.key" -out "$1.csr" 2>/dev/null
  openssl ca -batch -config ca.cnf -cert staff-ca.pem -keyfile staff-ca.key -in "$1.csr" -out "$1.pem" -notext 2>/dev/null
  rm -f "$1.csr"
}
staff_client alice   # a staff member
staff_client bob     # another one, to show a revocation does not touch the others

# A certificate from a CA nginx does not trust.
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj "/CN=mallory" -keyout mallory.key -out mallory.csr 2>/dev/null
printf 'extendedKeyUsage=clientAuth\nbasicConstraints=CA:FALSE\n' > mallory.ext
openssl x509 -req -in mallory.csr -CA rogue-ca.pem -CAkey rogue-ca.key -CAcreateserial -days "$DAYS" -extfile mallory.ext -out mallory.pem 2>/dev/null
rm -f mallory.csr mallory.ext

# The revocation list nginx reads (ssl_crl); empty for now.
openssl ca -config ca.cnf -cert staff-ca.pem -keyfile staff-ca.key -gencrl -out staff-ca.crl 2>/dev/null
chmod 600 ./*.key
echo "wrote $(pwd): staff-ca.pem, staff-ca.crl, server.pem/.key, alice and bob (valid clients), mallory (untrusted CA)"
