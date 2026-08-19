-- Ludo presence heartbeats (NIP-98 authenticated). Online = last_seen within ~90s.

CREATE TABLE IF NOT EXISTS presence (
  pubkey_hex TEXT PRIMARY KEY NOT NULL,
  last_seen INTEGER NOT NULL,
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE INDEX IF NOT EXISTS idx_presence_last_seen ON presence (last_seen DESC);
