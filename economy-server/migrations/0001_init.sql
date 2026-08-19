-- Phase 0 ledger: balances + ownership + immutable journal + auth replay + rate limits.
-- Money never mutates a bare integer without an accompanying ledger row.

CREATE TABLE IF NOT EXISTS accounts (
  pubkey_hex TEXT PRIMARY KEY NOT NULL,
  gold INTEGER NOT NULL DEFAULT 0 CHECK (gold >= 0),
  gems INTEGER NOT NULL DEFAULT 0 CHECK (gems >= 0),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ownership (
  pubkey_hex TEXT NOT NULL,
  item_kind TEXT NOT NULL, -- dice | piece | board | gift_skin
  item_id TEXT NOT NULL,
  acquired_at INTEGER NOT NULL,
  PRIMARY KEY (pubkey_hex, item_kind, item_id),
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE TABLE IF NOT EXISTS ledger (
  id TEXT PRIMARY KEY NOT NULL,
  pubkey_hex TEXT NOT NULL,
  kind TEXT NOT NULL, -- credit | debit | purchase | gift | daily | match_fee | match_reward | admin
  currency TEXT NOT NULL CHECK (currency IN ('gold', 'gems')),
  amount INTEGER NOT NULL CHECK (amount > 0),
  balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
  ref_type TEXT,
  ref_id TEXT,
  meta_json TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE INDEX IF NOT EXISTS idx_ledger_pubkey_time ON ledger (pubkey_hex, created_at DESC);

CREATE TABLE IF NOT EXISTS auth_replay (
  event_id TEXT PRIMARY KEY NOT NULL,
  pubkey_hex TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auth_replay_expires ON auth_replay (expires_at);

CREATE TABLE IF NOT EXISTS rate_limits (
  bucket_key TEXT PRIMARY KEY NOT NULL,
  window_start INTEGER NOT NULL,
  hit_count INTEGER NOT NULL DEFAULT 0
);
