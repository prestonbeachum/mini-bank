# Mini Bank

A synthetic core-banking backend: accounts, an immutable double-entry ledger, transfers, holds, and statements. **All money and customers are fake.** The banking domain is a vehicle for practising backend and cloud tooling: Postman, k6, Terraform, GitHub Actions, AWS, OpenTelemetry, Grafana, Sentry, Flyway, and a real multi-environment release process.

## Status

**Phase 1: ledger core.** Customers, accounts, deposits, transfers with `Idempotency-Key`, balances and keyset-paginated history, behind RS256 JWT auth with customer, ops and service roles. A property test drives random sequences of deposits, transfers and key replays against an in-memory model and checks ledger invariants 1 to 10 after every run. Next is phase 2, CI and packaging.

| Phase | What | Where |
|---|---|---|
| 0 | Toolchain and skeleton | local ✅ |
| 1 | Ledger core | local ✅ |
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

Run the collections with the [Postman CLI](https://learning.postman.com/docs/postman-cli/postman-cli-overview/):

```sh
postman collection run postman/collections/smoke    -e postman/environments/local.environment.yaml
postman collection run postman/collections/negative -e postman/environments/local.environment.yaml
```

Try it by hand (local-only credentials from `docker-compose.yml`):

```sh
OPS=$(curl -s localhost:8080/v1/auth/token -H 'Content-Type: application/json' \
  -d '{"client_id":"cli_ops_local","client_secret":"local-ops-secret"}' | jq -r .access_token)
curl -s localhost:8080/v1/customers -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.test","name":"Ada Example"}' | jq
```

The response includes the new customer's `client_id` and `client_secret`, shown once. Exchange them at `/v1/auth/token` for a customer token, then `POST /v1/accounts`. Deposits need the service client (`cli_service_local` / `local-service-secret`).

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

## Tests

| Test | What it proves |
|---|---|
| `LedgerInvariantPropertyTests` | 100 random sequences (deposits, transfers by owner / non-owner / service, same-body and different-body key replays) match an in-memory model; invariants 1–10 hold after each |
| `LedgerConcurrencyTests` | 400 opposite-direction transfers on 16 threads never deadlock; 100 parallel debits against funds for 10 let exactly 10 through; 50 parallel replays of one key post once |
| `SchemaConstraintTests` | The database alone rejects unbalanced or mixed-currency transactions, posting updates/deletes/truncates, overdrafts past the limit, and double reversals |
| `ApiFlowTests` | The HTTP contract: auth, role gates, 404 for foreign ids, problem+json errors, replay headers, pagination |

## Ground rules

- Money is `bigint` minor units plus a currency. No floating point near an amount.
- Postings are immutable. Corrections are new transactions.
- Every money-moving POST takes an `Idempotency-Key`.
- Never edit an applied migration.
- No real personal data or credentials anywhere in this repo.
