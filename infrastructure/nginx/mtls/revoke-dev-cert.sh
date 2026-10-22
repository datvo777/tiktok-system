#!/usr/bin/env bash
# Revokes a client certificate issued by gen-dev-certs.sh and rewrites the revocation list nginx reads.
#
#   revoke-dev-cert.sh alice            # list valid for 7 days
#   revoke-dev-cert.sh alice 5          # list valid for 5 seconds (to see what an expired list does)
#
# nginx reads the list when it starts or reloads, not on every handshake, so after running this:
#   docker exec sv-nginx-mtls nginx -s reload
set -euo pipefail
cd "$(dirname "$0")/generated"
name="$1"; seconds="${2:-}"
openssl ca -config ca.cnf -cert staff-ca.pem -keyfile staff-ca.key -revoke "$name.pem" -crl_reason keyCompromise 2>/dev/null
if [ -n "$seconds" ]; then
  openssl ca -config ca.cnf -cert staff-ca.pem -keyfile staff-ca.key -gencrl -crlsec "$seconds" -out staff-ca.crl 2>/dev/null
else
  openssl ca -config ca.cnf -cert staff-ca.pem -keyfile staff-ca.key -gencrl -out staff-ca.crl 2>/dev/null
fi
echo "revoked $name; $(openssl crl -in staff-ca.crl -noout -nextupdate)"
