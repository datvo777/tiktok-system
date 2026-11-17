# CI

`ci.yml` runs on every push to `main` and `dev`, and on every pull request.

| Job | What it proves |
| --- | --- |
| **Clients** (`web`, `admin-web`) | ESLint passes with zero warnings, TypeScript compiles, the bundle builds, and the unit tests pass. |
| **Backend** | `mvn verify` — the whole reactor compiles, unit tests pass, and the Testcontainers integration tests run against real Postgres, Redis, Kafka and MinIO. |

## Running the same checks locally

```bash
# Clients: npm workspaces, so install once at the repo root.
npm ci
npm run lint && npm run build && npm test

# Backend (needs Docker running for the integration tests)
mvn verify
```

`web`, `admin-web` and `shared` are npm workspaces. `shared` holds the HTTP and
response-validation layer both clients use; it was previously duplicated
byte-for-byte in each of them.

The backend job pins Java 21, matching `maven.compiler.release` in the root
`pom.xml`. A newer JDK compiles fine with `--release 21`, but pinning keeps CI
and the documented local setup describing the same thing.
