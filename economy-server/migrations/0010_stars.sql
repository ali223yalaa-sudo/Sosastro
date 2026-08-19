-- Stars currency + gift inventory. Client never mints stars.
-- Ledger CHECK must include 'stars'; SQLite cannot ALTER CHECK in place.

ALTER TABLE accounts ADD COLUMN stars INTEGER NOT NULL DEFAULT 0;

ALTER TABLE topup_orders ADD COLUMN stars INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS star_inventory (
  pubkey_hex TEXT NOT NULL,
  sku_id TEXT NOT NULL,
  qty INTEGER NOT NULL DEFAULT 0 CHECK (qty >= 0),
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (pubkey_hex, sku_id),
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE TABLE IF NOT EXISTS star_gifts (
  id TEXT PRIMARY KEY NOT NULL,
  from_pubkey TEXT NOT NULL,
  to_pubkey TEXT NOT NULL,
  note_id TEXT,
  sku_id TEXT NOT NULL,
  stars_value INTEGER NOT NULL CHECK (stars_value > 0),
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_star_gifts_note ON star_gifts (note_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_star_gifts_to ON star_gifts (to_pubkey, created_at DESC);

CREATE TABLE ledger_stars_mig (
  id TEXT PRIMARY KEY NOT NULL,
  pubkey_hex TEXT NOT NULL,
  kind TEXT NOT NULL,
  currency TEXT NOT NULL CHECK (currency IN ('gold', 'gems', 'stars')),
  amount INTEGER NOT NULL CHECK (amount > 0),
  balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
  ref_type TEXT,
  ref_id TEXT,
  meta_json TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

INSERT INTO ledger_stars_mig SELECT * FROM ledger;
DROP TABLE ledger;
ALTER TABLE ledger_stars_mig RENAME TO ledger;
CREATE INDEX IF NOT EXISTS idx_ledger_pubkey_time ON ledger (pubkey_hex, created_at DESC);
