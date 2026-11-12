# 6. The app connects as a least-privilege database role

Date: 2026-11-11 · Status: Accepted

## Context
Build doc section 5, invariant 2: postings are never updated or deleted, enforced by "a trigger that raises on UPDATE/DELETE, plus revoked privileges for the app role". Phase 1 shipped the trigger only, with the app connecting as the schema owner.

## Decision
- Flyway runs as the schema owner; the app connects as a login that is a member of `ledger_app`.
- `V003` creates `ledger_app` if missing and grants it `SELECT, INSERT` on postings, and column-level `UPDATE` only on the columns the code changes (balances, statuses, idempotency responses, outbox `published_at`, client secrets). No `DELETE` or `TRUNCATE` anywhere, no DDL, no access to `flyway_schema_history`.
- Convention: every migration that adds a table grants `ledger_app` explicitly. No default privileges, so a new table is inaccessible until a reviewed migration says otherwise.
- The login itself is environment infrastructure: `db/local/roles.sql` locally and in tests, Terraform in AWS (phase 3).

## Consequences
Integration, property and concurrency tests all run as the app login, so a missing grant fails the build. Schema tests prove the app login gets "permission denied" for UPDATE/DELETE/TRUNCATE on postings, for rewriting transactions, and for dropping the immutability trigger. Developers with an old local volume run `docker compose down -v` once.
