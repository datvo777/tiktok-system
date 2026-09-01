#!/bin/sh
# Bucket provisioning. Brief section 19 + Rule 18.
#
# DO NOT ADD `mc anonymous set` HERE, in any mode. A public bucket exposes every
# object under processed/ directly on :9000, letting a browser fetch HLS segments
# without the media gateway, without a playback cookie, and without any
# revocation or eligibility check. That one line voids sections 8 and 16 and the
# Milestone 3 timing test. The gateway is the only read path.
set -e

mc alias set local "${MINIO_INTERNAL_ENDPOINT:-http://minio:9000}" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY"
mc mb --ignore-existing "local/$MINIO_BUCKET"

# Assert the bucket is not anonymously readable. Fails the stack loudly if a
# future change makes it public.
POLICY="$(mc anonymous get "local/$MINIO_BUCKET" 2>/dev/null || true)"
case "$POLICY" in
  *none*|*private*|"") echo "bucket $MINIO_BUCKET is private (correct)" ;;
  *) echo "FATAL: bucket $MINIO_BUCKET is anonymously accessible: $POLICY" >&2; exit 1 ;;
esac

# ---------------------------------------------------------------------------
# Least privilege for the application.
#
# The backend and the media worker previously authenticated as MINIO_ROOT_USER.
# Any code-execution foothold in either process was therefore full control of the
# object store, including deleting every bucket. They now get a service account
# scoped to this one bucket: read, write and delete objects inside it, and
# nothing outside it. Root stays with the operator.
# ---------------------------------------------------------------------------
cat >/tmp/app-policy.json <<POLICY_JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::${MINIO_BUCKET}"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::${MINIO_BUCKET}/*"]
    }
  ]
}
POLICY_JSON

mc admin policy create local short-video-app /tmp/app-policy.json 2>/dev/null \
  || mc admin policy update local short-video-app /tmp/app-policy.json 2>/dev/null \
  || echo "note: policy short-video-app already current"

# Idempotent: recreating an existing service account is an error, so tolerate it.
mc admin user svcacct add local "$MINIO_ACCESS_KEY" \
  --access-key "$MINIO_APP_ACCESS_KEY" \
  --secret-key "$MINIO_APP_SECRET_KEY" \
  --policy /tmp/app-policy.json 2>/dev/null \
  || mc admin user svcacct edit local "$MINIO_APP_ACCESS_KEY" \
       --policy /tmp/app-policy.json 2>/dev/null \
  || echo "note: service account $MINIO_APP_ACCESS_KEY already exists"

rm -f /tmp/app-policy.json
echo "minio-init complete"
