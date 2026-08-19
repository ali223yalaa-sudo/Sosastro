-- User location for Around Me (country/region). No raw IP stored.
ALTER TABLE accounts ADD COLUMN loc_consent_at INTEGER;
ALTER TABLE accounts ADD COLUMN loc_country TEXT;
ALTER TABLE accounts ADD COLUMN loc_region TEXT;
ALTER TABLE accounts ADD COLUMN loc_geohash TEXT;
ALTER TABLE accounts ADD COLUMN loc_lat REAL;
ALTER TABLE accounts ADD COLUMN loc_lon REAL;
ALTER TABLE accounts ADD COLUMN loc_updated_at INTEGER;

CREATE INDEX IF NOT EXISTS idx_accounts_loc_country
  ON accounts (loc_country, loc_updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_accounts_loc_geohash
  ON accounts (loc_country, loc_geohash);
