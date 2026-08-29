#!/bin/sh
# Bucket provisioning. Brief section 19 + Rule 18.
#
# DO NOT ADD `mc anonymous set` HERE, in any mode. A public bucket exposes every
# object under processed/ directly on :9000, letting a browser fetch HLS segments
# without the media gateway, without a playback cookie, and without any
# revocation or eligibility check. That one line voids sections 8 and 16 and the
# Milestone 3 timing test. The gateway is the only read path.
set -e

mc alias set local "http://minio:9000" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY"
mc mb --ignore-existing "local/$MINIO_BUCKET"

# Assert the bucket is not anonymously readable. Fails the stack loudly if a
# future change makes it public.
POLICY="$(mc anonymous get "local/$MINIO_BUCKET" 2>/dev/null || true)"
case "$POLICY" in
  *none*|*private*|"") echo "bucket $MINIO_BUCKET is private (correct)" ;;
  *) echo "FATAL: bucket $MINIO_BUCKET is anonymously accessible: $POLICY" >&2; exit 1 ;;
esac

echo "minio-init complete"
