import { ensureAccount } from "./ledger";
import { countryFromRequest } from "./geo";

const GEOHASH_RE = /^[0-9b-hjkmnp-z]{1,12}$/i;
const REGION_RE = /^[\p{L}\p{N}\s\-_.']{1,64}$/u;
const NEARBY_LIMIT = 1500;
/** Shared geohash prefix length ≈ city / tens of km. */
const REGION_PREFIX_LEN = 4;

export type LocationRow = {
  loc_consent_at: number | null;
  loc_country: string | null;
  loc_region: string | null;
  loc_geohash: string | null;
  loc_lat: number | null;
  loc_lon: number | null;
  loc_updated_at: number | null;
};

export type LocationUpdateInput = {
  consent: true;
  lat?: number | null;
  lon?: number | null;
  geohash?: string | null;
  region?: string | null;
};

function clampCoord(n: unknown, min: number, max: number): number | null {
  if (typeof n !== "number" || !Number.isFinite(n)) return null;
  if (n < min || n > max) return null;
  return n;
}

export function normalizeGeohash(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const g = raw.trim().toLowerCase();
  if (!GEOHASH_RE.test(g)) return null;
  return g;
}

export function normalizeRegion(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const r = raw.trim();
  if (!r || !REGION_RE.test(r)) return null;
  return r.slice(0, 64);
}

export async function saveUserLocation(
  db: D1Database,
  pubkey: string,
  country: string,
  input: LocationUpdateInput,
  now: number,
): Promise<LocationRow> {
  await ensureAccount(db, pubkey, now);
  const existing = await getUserLocation(db, pubkey);
  const lat = clampCoord(input.lat, -90, 90);
  const lon = clampCoord(input.lon, -180, 180);
  const geohash = normalizeGeohash(input.geohash) ?? existing?.loc_geohash ?? null;
  const region = normalizeRegion(input.region) ?? existing?.loc_region ?? null;
  const nextLat = lat ?? existing?.loc_lat ?? null;
  const nextLon = lon ?? existing?.loc_lon ?? null;

  await db
    .prepare(
      `UPDATE accounts SET
         loc_consent_at = COALESCE(loc_consent_at, ?),
         loc_country = ?,
         loc_region = ?,
         loc_geohash = ?,
         loc_lat = ?,
         loc_lon = ?,
         loc_updated_at = ?,
         updated_at = ?
       WHERE pubkey_hex = ?`,
    )
    .bind(
      now,
      country,
      region,
      geohash,
      nextLat,
      nextLon,
      now,
      now,
      pubkey,
    )
    .run();

  return (await getUserLocation(db, pubkey))!;
}

export async function getUserLocation(
  db: D1Database,
  pubkey: string,
): Promise<LocationRow | null> {
  return (
    (await db
      .prepare(
        `SELECT loc_consent_at, loc_country, loc_region, loc_geohash, loc_lat, loc_lon, loc_updated_at
         FROM accounts WHERE pubkey_hex = ?`,
      )
      .bind(pubkey)
      .first<LocationRow>()) ?? null
  );
}

/**
 * Nearby authors by country, preferring same geohash-region prefix when available.
 * Does not use Nostr hashtags — only server-stored location profiles.
 */
export async function listNearbyAuthors(
  db: D1Database,
  me: LocationRow,
  scope: "country" | "region",
): Promise<{ country: string; scope: string; authors: string[] }> {
  const country = (me.loc_country || "").toUpperCase();
  if (!country || country === "XX") {
    return { country: country || "XX", scope, authors: [] };
  }

  if (scope === "region" && me.loc_geohash && me.loc_geohash.length >= REGION_PREFIX_LEN) {
    const prefix = me.loc_geohash.slice(0, REGION_PREFIX_LEN).toLowerCase();
    const rows = await db
      .prepare(
        `SELECT pubkey_hex FROM accounts
         WHERE loc_consent_at IS NOT NULL
           AND loc_country = ?
           AND loc_geohash IS NOT NULL
           AND lower(substr(loc_geohash, 1, ?)) = ?
         ORDER BY loc_updated_at DESC
         LIMIT ?`,
      )
      .bind(country, REGION_PREFIX_LEN, prefix, NEARBY_LIMIT)
      .all<{ pubkey_hex: string }>();
    const authors = (rows.results || []).map((r) => r.pubkey_hex);
    if (authors.length > 0) {
      return { country, scope: "region", authors };
    }
  }

  const rows = await db
    .prepare(
      `SELECT pubkey_hex FROM accounts
       WHERE loc_consent_at IS NOT NULL
         AND loc_country = ?
       ORDER BY loc_updated_at DESC
       LIMIT ?`,
    )
    .bind(country, NEARBY_LIMIT)
    .all<{ pubkey_hex: string }>();

  return {
    country,
    scope: "country",
    authors: (rows.results || []).map((r) => r.pubkey_hex),
  };
}

export { countryFromRequest };
