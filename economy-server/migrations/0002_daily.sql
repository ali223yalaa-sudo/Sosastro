-- Phase 2: daily claim tracking (UTC calendar day). App wipe cannot re-grant.

CREATE TABLE IF NOT EXISTS daily_claims (
  pubkey_hex TEXT NOT NULL,
  day_utc TEXT NOT NULL,
  claimed_at INTEGER NOT NULL,
  gold_granted INTEGER NOT NULL,
  gems_granted INTEGER NOT NULL,
  PRIMARY KEY (pubkey_hex, day_utc),
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE INDEX IF NOT EXISTS idx_daily_claims_day ON daily_claims (day_utc);
