# 4. API client credentials and idempotency semantics

Date: 2026-11-11 · Status: Accepted

## Context
The build doc says "JWT auth with roles (customer, ops, service)" and "dev and staging use seeded users", and that a repeated Idempotency-Key returns the original result. It does not say how customers get credentials, or what happens to a key whose first request failed.

## Decision
- **Credentials.** `POST /v1/auth/token` exchanges a `client_id` and `client_secret` (bcrypt-hashed in `api_clients`) for a 15-minute RS256 JWT carrying `role` and, for customers, `customer_id`. Ops and service clients are provisioned from configuration at startup (secrets from the environment locally, SSM Parameter Store in AWS). Customer credentials are generated when ops creates the customer and returned once.
- **Signing key.** PKCS#8 PEM from `MINIBANK_JWT_PRIVATE_KEY`; when blank, an ephemeral key is generated with a warning. Shared environments must set it.
- **Idempotency.** Keys are scoped to the JWT subject and bind only a *successful* response, for 24 hours. A request that fails a business rule rolls back with its key, so the client can retry with the same key. Same key with a different body after success is 422.
- **Visibility.** A resource the caller may not see is 404, never 403. 403 is reserved for a role that may not call the route at all.

## Consequences
Retries after a transient failure are safe and need no new key. A client that gets 422 for insufficient funds and retries the same key later, after a deposit, can succeed: that is intended. Key expiry is enforced on claim; a cleanup job for old rows arrives with the scheduler in phase 6.
