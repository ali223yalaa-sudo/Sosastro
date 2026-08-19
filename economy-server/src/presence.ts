/**
 * Presence heartbeats — NIP-98 only. Clients cannot spoof another pubkey.
 * Online window: 90 seconds from last signed heartbeat.
 */
import type { Env } from "./ledger";
import { ensureAccount } from "./ledger";

export const PRESENCE_ONLINE_SEC = 90;

export async function heartbeatPresence(db: D1Database, pubkey: string, nowSec: number): Promise<void> {
  await ensureAccount(db, pubkey, nowSec);
  await db
    .prepare(
      `INSERT INTO presence (pubkey_hex, last_seen) VALUES (?, ?)
       ON CONFLICT(pubkey_hex) DO UPDATE SET last_seen = excluded.last_seen`,
    )
    .bind(pubkey.toLowerCase(), nowSec)
    .run();
}

export async function queryPresence(
  db: D1Database,
  pubkeys: string[],
  nowSec: number,
): Promise<Record<string, boolean>> {
  const out: Record<string, boolean> = {};
  const unique = [...new Set(pubkeys.map((p) => p.toLowerCase()).filter((p) => /^[0-9a-f]{64}$/.test(p)))].slice(
    0,
    50,
  );
  for (const p of unique) out[p] = false;
  if (unique.length === 0) return out;

  const placeholders = unique.map(() => "?").join(",");
  const cutoff = nowSec - PRESENCE_ONLINE_SEC;
  const rows = await db
    .prepare(`SELECT pubkey_hex FROM presence WHERE pubkey_hex IN (${placeholders}) AND last_seen >= ?`)
    .bind(...unique, cutoff)
    .all<{ pubkey_hex: string }>();

  for (const row of rows.results ?? []) {
    out[row.pubkey_hex] = true;
  }
  return out;
}
