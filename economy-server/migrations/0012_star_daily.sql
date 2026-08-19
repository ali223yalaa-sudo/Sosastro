-- Daily star gift: one claim per pubkey per UTC day. App wipe cannot re-grant.
CREATE TABLE IF NOT EXISTS star_daily_claims (
  pubkey_hex TEXT NOT NULL,
  day_utc TEXT NOT NULL,
  claimed_at INTEGER NOT NULL,
  stars_granted INTEGER NOT NULL CHECK (stars_granted > 0),
  PRIMARY KEY (pubkey_hex, day_utc),
  FOREIGN KEY (pubkey_hex) REFERENCES accounts(pubkey_hex)
);

CREATE INDEX IF NOT EXISTS idx_star_daily_day ON star_daily_claims (day_utc);
