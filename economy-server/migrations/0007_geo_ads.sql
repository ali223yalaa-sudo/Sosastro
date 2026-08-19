-- Geo ads consent: country code only (no raw IP stored).
ALTER TABLE accounts ADD COLUMN ad_country TEXT;
ALTER TABLE accounts ADD COLUMN ad_consent_at INTEGER;
