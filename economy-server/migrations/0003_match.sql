-- Phase 3: match tickets + one payout per match (server→server only for settle).

CREATE TABLE IF NOT EXISTS match_tickets (
  id TEXT PRIMARY KEY NOT NULL,
  pubkey_hex TEXT NOT NULL,
  mode TEXT NOT NULL,
  amount INTEGER NOT NULL CHECK (amount > 0),
  status TEXT NOT NULL CHECK (status IN ('open', 'bound', 'refunded', 'lost')),
  match_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE INDEX IF NOT EXISTS idx_match_tickets_pubkey_status ON match_tickets (pubkey_hex, status);
CREATE INDEX IF NOT EXISTS idx_match_tickets_match ON match_tickets (match_id);

CREATE TABLE IF NOT EXISTS match_payouts (
  match_id TEXT PRIMARY KEY NOT NULL,
  mode TEXT NOT NULL,
  winner_pubkey TEXT,
  amount INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
