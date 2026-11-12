-- V003: the application runs as a member of ledger_app, never as the schema owner.
-- Flyway (the owner) creates and alters tables; the app can only do what is granted here.
-- Convention: every migration that adds a table grants ledger_app explicitly. No defaults.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
    CREATE ROLE ledger_app NOLOGIN;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO ledger_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO ledger_app;

-- Invariant 2, second layer: postings are insert-only for the app. The trigger is the first layer.
GRANT SELECT, INSERT ON postings TO ledger_app;

GRANT SELECT, INSERT ON customers, accounts, transactions, idempotency_keys, outbox_events, api_clients TO ledger_app;

-- Column-level UPDATE: only the columns the code is meant to change.
GRANT UPDATE (status) ON customers TO ledger_app;
GRANT UPDATE (balance_minor, holds_minor, overdraft_limit_minor, status, version) ON accounts TO ledger_app;
GRANT UPDATE (status) ON transactions TO ledger_app;
GRANT UPDATE (request_hash, response_status, response_body, created_at) ON idempotency_keys TO ledger_app;
GRANT UPDATE (published_at) ON outbox_events TO ledger_app;
GRANT UPDATE (secret_hash, role, disabled_at) ON api_clients TO ledger_app;
