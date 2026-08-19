import { ensureAccount, type Env } from "./ledger";

export type GeoConsentRow = {
  ad_country: string | null;
  ad_consent_at: number | null;
};

/** ISO country from Cloudflare, or XX when unavailable (e.g. local wrangler). */
export function countryFromRequest(request: Request): string {
  const cf = request.cf as { country?: string } | undefined;
  const raw = cf?.country?.trim().toUpperCase();
  if (raw && /^[A-Z]{2}$/.test(raw)) return raw;
  return "XX";
}

export async function saveGeoConsent(
  db: D1Database,
  pubkey: string,
  country: string,
  now: number,
): Promise<void> {
  await ensureAccount(db, pubkey, now);
  await db
    .prepare(
      `UPDATE accounts
       SET ad_country = ?, ad_consent_at = ?, updated_at = ?
       WHERE pubkey_hex = ?`,
    )
    .bind(country, now, now, pubkey)
    .run();
}

export async function getGeoConsent(
  db: D1Database,
  pubkey: string,
): Promise<GeoConsentRow | null> {
  const row = await db
    .prepare(`SELECT ad_country, ad_consent_at FROM accounts WHERE pubkey_hex = ?`)
    .bind(pubkey)
    .first<GeoConsentRow>();
  return row ?? null;
}
