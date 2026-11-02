CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE wallets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), owner_id UUID NOT NULL, currency CHAR(3) NOT NULL,
  balance_minor BIGINT NOT NULL DEFAULT 0 CHECK (balance_minor >= 0), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(owner_id, currency)
);
CREATE TABLE transfers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  from_wallet UUID NOT NULL REFERENCES wallets(id), to_wallet UUID NOT NULL REFERENCES wallets(id),
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0), currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL CHECK(status IN ('COMPLETED','FAILED')), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ledger_entries (
  id BIGSERIAL PRIMARY KEY, transfer_id UUID NOT NULL REFERENCES transfers(id), wallet_id UUID REFERENCES wallets(id), account_code VARCHAR(64),
  direction VARCHAR(6) NOT NULL CHECK(direction IN ('DEBIT','CREDIT')), amount_minor BIGINT NOT NULL CHECK(amount_minor > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), CHECK ((wallet_id IS NULL) <> (account_code IS NULL))
);
CREATE INDEX ledger_wallet_history ON ledger_entries(wallet_id, id DESC);
CREATE TABLE outbox (
  id BIGSERIAL PRIMARY KEY, aggregate_id UUID NOT NULL, event_type VARCHAR(64) NOT NULL, payload JSONB NOT NULL,
  published BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX outbox_pending ON outbox(id) WHERE published = FALSE;
CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY, actor_id UUID NOT NULL, action VARCHAR(64) NOT NULL, target_id UUID,
  details JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE notifications (
  event_id BIGINT PRIMARY KEY, aggregate_id UUID NOT NULL, event_type VARCHAR(64) NOT NULL, payload JSONB NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
