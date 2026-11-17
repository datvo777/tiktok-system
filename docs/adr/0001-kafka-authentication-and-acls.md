# ADR 0001: Kafka authentication and ACLs

Status: accepted for the local stack; production changes listed below.

## Context

The local broker was plaintext with no authorization on every listener, and the host listener
was published on all interfaces. Anything that could reach port 29092 could read every event
(accounts, moderation decisions, notifications) and write any of them, including forged
`media.results.v1` messages that the Video module would act on. Kafka UI could also add
clusters at runtime.

## Decision

Two layers, so the cheap part does not wait for the expensive one.

**Always on (default stack).** Host listener and Kafka UI bind to `127.0.0.1`. Kafka UI's
dynamic config is off. No credentials involved, no change to dev or CI.

**Opt-in (`docker-compose.secure.yml`).**

- SASL/SCRAM-SHA-512. Chosen over PLAIN (password in the clear unless TLS is perfect, and the
  broker holds it in config) and over mTLS/OAUTHBEARER (needs a PKI or an IdP this project does
  not have). SCRAM users are created when storage is formatted (`--add-scram`, KIP-900), so
  there is no unauthenticated bootstrap window.
- TLS on the host listener only. `INTERNAL` (containers) and `CONTROLLER` (quorum) stay
  plaintext at the transport level but still require a SCRAM login; they never leave the
  compose network.
- `StandardAuthorizer`, `allow.everyone.if.no.acl.found=false`, and `admin` as the only super
  user. `admin` is used by `kafka-init` and the broker itself, never by the applications.
- One principal per process, so a compromised worker cannot forge domain events.

## ACL matrix

Derived from the code (`TopicResolver`, `@KafkaListener`, the dead-letter recoverers), not from
the topic list. `init-topics-and-acls.sh` is the executable version.

| Principal | Topics | Groups | Cluster |
| --- | --- | --- | --- |
| `backend` | Write `video.events.v1`, `account.events.v1`, `social.events.v1`, `notification.events.v1`, `media.jobs.v1`. Write `*.DLT` of the five topics it consumes. Read the five it consumes (`video`, `account`, `social`, `media.results`, `notification`) and all six `*.DLT`. | Read: the 14 groups in `acl-groups.txt`, plus prefix `realtime-` | Describe |
| `media-worker` | Read `media.jobs.v1`. Write `media.results.v1`, `media.jobs.v1.DLT`. | Read: `media-worker` | none |
| `kafka-ui` | Read, Describe, DescribeConfigs on all topics | Describe all | Describe, DescribeConfigs |
| `admin` | super user | | |

Things that are easy to get wrong, each of which stalls the application silently:

- **Every consumer group needs its own Read ACL.** `check-acl-groups.sh` fails when
  `acl-groups.txt` and the `groupId` strings in the code disagree. `RealtimeListener` appends
  a random UUID per instance, so it is one `PREFIXED` ACL (`realtime-`).
- **The DLT alert listener subscribes by pattern** (`.*\.DLT`). ACLs support literals and
  prefixes, not suffixes, so each DLT is granted by name. A new topic means a new line.
- **`KafkaHealthIndicator` creates its own `AdminClient`** and `describeCluster` needs
  `Describe` on the cluster. Without either the readiness probe reports DOWN while everything
  else works.
- **Both producer factories are built by hand**, which disables Spring Boot's. They now take
  their settings from `KafkaProperties` (`KafkaConfig`, `WorkerKafkaConfig`,
  `KafkaHealthIndicator`); `KafkaConfigTest` pins that. Consumers need no code change.
- Idempotent producers need no cluster-level ACL on Kafka 3.x when the principal can write a
  topic (verified, not assumed).

## Verification

`infrastructure/kafka/secure/verify.sh` runs 18 checks against the HOST listener: an anonymous
client and a wrong password are refused; each principal can do its own job; and each is told
"not authorized", by the broker, when it tries the others' (worker writing `video.events.v1`,
backend writing `media.results.v1`, anything creating topics, an unlisted group, Kafka UI
writing). A refusal only counts if the broker says so. A timeout or dropped connection is what
a misconfigured client looks like too, and counting it would pass the test for free.

The same principals were also exercised through the Spring beans: with `client-backend.env`
loaded, `KafkaHealthIndicator` reports UP, a write to `video.events.v1` succeeds and a write to
`media.results.v1` fails with `TopicAuthorizationException`.

Not done: the end-to-end upload, transcode, READY flow against the secure broker, and a
Testcontainers IT for it. The Kafka Testcontainers suites already fail to start on Docker
Engine 29 (see README, "Known issue"), so a secure variant would not run here either.

## Rotating a credential

SCRAM users are written when the broker's storage is formatted, so in the local stack
rotation is: `gen-secrets.sh --force`, recreate the stack, restart the clients with the new
`client-*.env`. The broker has no volume, so nothing else is lost. On a real cluster use
`kafka-configs --alter --add-config 'SCRAM-SHA-512=[password=...]' --entity-type users`; a user
has one live password at a time, so for zero downtime add a second principal, switch the
client, then delete the old one.

## For production

- Secrets from a secret manager, not `.env` and files in the repo tree.
- Certificates from the company PKI with real hostnames; TLS on `INTERNAL` and `CONTROLLER` too
  (`SASL_SSL` everywhere), dedicated controllers, replication factor 3.
- Kafka UI behind SSO, or removed.
- If an identity provider exists, OAUTHBEARER; if a PKI exists, mTLS. Both remove shared
  passwords.
- ACLs applied by a reviewed pipeline, not a one-shot container.
