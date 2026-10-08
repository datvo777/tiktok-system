#!/usr/bin/env bash
# Fails when the backend's consumer groups and acl-groups.txt disagree. A group missing from
# the file means that listener is denied at runtime and just sits there; run this in CI or
# before touching a @KafkaListener.
set -euo pipefail
cd "$(dirname "$0")/../../.."

in_code=$(grep -rhoE 'groupId = "[^"#]+"' --include='*.java' --exclude-dir=test backend \
  | sed -E 's/groupId = "(.*)"/\1/' | sort -u)
in_file=$(grep -vE '^\s*(#|$)' infrastructure/kafka/secure/acl-groups.txt | sort -u)

if ! diff <(echo "$in_code") <(echo "$in_file"); then
  echo "acl-groups.txt is out of step with the @KafkaListener groupIds (< code, > file)" >&2
  exit 1
fi
echo "acl-groups.txt matches the code ($(echo "$in_code" | wc -l | tr -d ' ') groups)"
