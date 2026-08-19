-- Phase 4: real-money top-up orders (credit only via signed webhook / admin fulfill).

CREATE TABLE IF NOT EXISTS topup_orders (
  id TEXT PRIMARY KEY NOT NULL,
  pubkey_hex TEXT NOT NULL,
  package_id TEXT NOT NULL,
  gold INTEGER NOT NULL DEFAULT 0 CHECK (gold >= 0),
  gems INTEGER NOT NULL DEFAULT 0 CHECK (gems >= 0),
  price_cents INTEGER NOT NULL CHECK (price_cents >= 0),
  currency_code TEXT NOT NULL DEFAULT 'USD',
  status TEXT NOT NULL CHECK (status IN ('pending', 'paid', 'cancelled', 'failed')),
  provider TEXT,
  provider_event_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  paid_at INTEGER,
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE INDEX IF NOT EXISTS idx_topup_orders_pubkey ON topup_orders (pubkey_hex, created_at);
CREATE INDEX IF NOT EXISTS idx_topup_orders_status ON topup_orders (status);

-- Provider event idempotency (Stripe event id / Zain txn id / admin key).
CREATE TABLE IF NOT EXISTS topup_events (
  provider_event_id TEXT PRIMARY KEY NOT NULL,
  order_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (order_id) REFERENCES topup_orders(id)
);
