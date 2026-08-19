-- Player level / XP (server-authoritative). Awards via signed game worker or future verified tasks.

CREATE TABLE IF NOT EXISTS player_progress (
  pubkey_hex TEXT PRIMARY KEY NOT NULL,
  total_xp INTEGER NOT NULL DEFAULT 0 CHECK (total_xp >= 0),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE TABLE IF NOT EXISTS xp_events (
  id TEXT PRIMARY KEY NOT NULL,
  pubkey_hex TEXT NOT NULL,
  action TEXT NOT NULL,
  xp INTEGER NOT NULL CHECK (xp > 0),
  ref_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (pubkey_hex, action, ref_id),
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE INDEX IF NOT EXISTS idx_xp_events_pubkey_time ON xp_events (pubkey_hex, created_at DESC);
