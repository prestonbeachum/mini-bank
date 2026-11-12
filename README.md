# Mini Bank

A synthetic core-banking backend: accounts, an immutable double-entry ledger, transfers, holds, and statements. **All money and customers are fake.** The banking domain is a vehicle for practising backend and cloud tooling: Postman, k6, Terraform, GitHub Actions, AWS, OpenTelemetry, Grafana, Sentry, Flyway, and a real multi-environment release process.

## Status

**Phase 0: toolchain and skeleton.** `docker compose up` serves `/health`, and the Postman smoke collection passes. Next is phase 1, the ledger core (schema, deposits, transfers, idempotency, property tests).

| Phase | What | Where |
|---|---|---|
| 0 | Toolchain and skeleton | local ✅ |
| 1 | Ledger core | local |
| 2 | CI and packaging | local |
| 3 | Environments and deploy | AWS (free-plan clock starts) |
| 4 | Observability and Sentry | AWS |
| 5 | k6 suite and reconciliation gate | AWS |
| 6 | Cool tier: holds, reversals, webhooks, statements | AWS |
| 7 | Hardening and game day | AWS |
| 8 | Stretch (optional) | AWS |

## Run it locally

Requires Docker and Java 21. Maven comes from the wrapper.

```sh
docker compose up -d --build
curl localhost:8080/health
```

Postgres is exposed on host port **5433** (override with `MINIBANK_PG_PORT`) so it doesn't collide with another local Postgres.

Run the tests (Testcontainers starts its own Postgres):

```sh
cd app && ./mvnw verify
```

Run the smoke collection with the [Postman CLI](https://learning.postman.com/docs/postman-cli/postman-cli-overview/):

```sh
postman collection run postman/collections/smoke -e postman/environments/local.environment.yaml
```

## Layout

```
api/                openapi.yaml, the contract, written before the code
app/                Spring Boot 4, Java 21
db/migrations/      Flyway, forward-only (packaged into the app at build time)
infra/              Terraform (phase 3)
perf/               k6 scripts (phase 5)
postman/            v3 collections and environments
docs/adr/           architecture decision records
docs/runbooks/      one per alert
.github/workflows/  ci
docker-compose.yml  app + Postgres
```

## Ground rules

- Money is `bigint` minor units plus a currency. No floating point near an amount.
- Postings are immutable. Corrections are new transactions.
- Every money-moving POST takes an `Idempotency-Key`.
- Never edit an applied migration.
- No real personal data or credentials anywhere in this repo.
