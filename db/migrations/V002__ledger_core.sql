-- V002: Core-tier ledger schema (build doc sections 4 and 5).
-- Money is bigint minor units. Ids are app-generated UUIDv7. Timestamps are timestamptz (UTC).

CREATE TABLE customers (
  id          uuid PRIMARY KEY,
  email       text NOT NULL,
  name        text NOT NULL,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX customers_email_uq ON customers (lower(email));

CREATE TABLE accounts (
  id                    uuid PRIMARY KEY,
  customer_id           uuid REFERENCES customers(id),
  kind                  text NOT NULL CHECK (kind IN ('CHECKING','SAVINGS','INTERNAL')),
  currency              char(3) NOT NULL DEFAULT 'USD',
  status                text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
  balance_minor         bigint NOT NULL DEFAULT 0,
  holds_minor           bigint NOT NULL DEFAULT 0 CHECK (holds_minor >= 0),
  overdraft_limit_minor bigint NOT NULL DEFAULT 0 CHECK (overdraft_limit_minor >= 0),
  version               bigint NOT NULL DEFAULT 0,
  created_at            timestamptz NOT NULL DEFAULT now(),
  CHECK ((kind = 'INTERNAL') = (customer_id IS NULL)),
  -- Invariants 3 and 10: a customer account never goes below -overdraft_limit after holds.
  CONSTRAINT accounts_available_funds_check
    CHECK (kind = 'INTERNAL' OR balance_minor - holds_minor + overdraft_limit_minor >= 0)
);
CREATE INDEX accounts_customer_idx ON accounts (customer_id);

CREATE TABLE transactions (
  id                       uuid PRIMARY KEY,
  type                     text NOT NULL CHECK (type IN ('DEPOSIT','TRANSFER')),
  status                   text NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','REVERSED')),
  currency                 char(3) NOT NULL,
  -- Invariant 4: a transaction can be reversed at most once.
  reverses_transaction_id  uuid UNIQUE REFERENCES transactions(id),
  reference                text,
  metadata                 jsonb NOT NULL DEFAULT '{}',
  posted_at                timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE postings (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id  uuid NOT NULL REFERENCES transactions(id),
  account_id      uuid NOT NULL REFERENCES accounts(id),
  amount_minor    bigint NOT NULL CHECK (amount_minor <> 0),
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX postings_account_created_idx ON postings (account_id, created_at DESC, id DESC);
CREATE INDEX postings_txn_idx ON postings (transaction_id);

-- Invariants 1 and 7, checked once at commit so postings can be inserted one at a time:
-- every transaction's postings sum to zero, and every posting's account shares the
-- transaction's currency.
CREATE FUNCTION assert_txn_balanced() RETURNS trigger AS $$
BEGIN
  IF (SELECT COALESCE(SUM(amount_minor), 0) FROM postings
      WHERE transaction_id = NEW.transaction_id) <> 0 THEN
    RAISE EXCEPTION 'transaction % is not balanced', NEW.transaction_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF EXISTS (SELECT 1 FROM postings p
             JOIN accounts a ON a.id = p.account_id
             JOIN transactions t ON t.id = p.transaction_id
             WHERE p.transaction_id = NEW.transaction_id AND a.currency <> t.currency) THEN
    RAISE EXCEPTION 'transaction % mixes currencies', NEW.transaction_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER postings_balanced
  AFTER INSERT ON postings
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_txn_balanced();

-- Invariant 2: postings are never updated, deleted or truncated.
CREATE FUNCTION reject_posting_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'postings are immutable (% rejected)', TG_OP
    USING ERRCODE = 'restrict_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER postings_immutable
  BEFORE UPDATE OR DELETE ON postings
  FOR EACH ROW EXECUTE FUNCTION reject_posting_mutation();
CREATE TRIGGER postings_no_truncate
  BEFORE TRUNCATE ON postings
  FOR EACH STATEMENT EXECUTE FUNCTION reject_posting_mutation();

-- Invariant 5: replay protection. Inserted in the same DB transaction as the money move.
CREATE TABLE idempotency_keys (
  scope            text NOT NULL,          -- caller identity (JWT subject)
  key              text NOT NULL,
  request_hash     bytea NOT NULL,         -- same key + different body = 422
  response_status  int,
  response_body    jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (scope, key)
);
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);

-- Invariant 6: an event exists if and only if its ledger write committed.
CREATE TABLE outbox_events (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id      uuid NOT NULL UNIQUE,      -- stable id consumers dedupe on
  event_type    text NOT NULL,
  payload       jsonb NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  published_at  timestamptz
);
CREATE INDEX outbox_unpublished_idx ON outbox_events (id) WHERE published_at IS NULL;

-- Credentials for POST /v1/auth/token. Secrets are stored only as bcrypt hashes.
CREATE TABLE api_clients (
  client_id    text PRIMARY KEY,
  secret_hash  text NOT NULL,
  role         text NOT NULL CHECK (role IN ('customer','ops','service')),
  customer_id  uuid REFERENCES customers(id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  disabled_at  timestamptz,
  CHECK ((role = 'customer') = (customer_id IS NOT NULL))
);

-- Internal accounts with fixed ids. Settlement is the counterparty for external money.
INSERT INTO accounts (id, kind, currency) VALUES
  ('00000000-0000-7000-8000-000000000001', 'INTERNAL', 'USD'),  -- settlement
  ('00000000-0000-7000-8000-000000000002', 'INTERNAL', 'USD');  -- fees
