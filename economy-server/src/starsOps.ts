/**
 * Stars + gifts. Prices and balances live only on the server.
 * Buy/send use balance-guarded UPDATEs + unique ledger ids (idempotent).
 */
import { utcDay } from "./catalog";
import { credit, ensureAccount, getWallet } from "./ledger";
import { DAILY_STARS, findGiftSku, findStarPackage, type GiftSku } from "./starsCatalog";

const HEX64 = /^[0-9a-f]{64}$/;

export function isHex64(s: string): boolean {
  return HEX64.test(s);
}

export async function starDailyStatus(db: D1Database, pubkey: string, now: number) {
  const day = utcDay(now);
  const row = await db
    .prepare(
      `SELECT claimed_at, stars_granted FROM star_daily_claims WHERE pubkey_hex = ? AND day_utc = ?`,
    )
    .bind(pubkey, day)
    .first<{ claimed_at: number; stars_granted: number }>();
  return {
    dayUtc: day,
    claimed: !!row,
    claimedAt: row?.claimed_at ?? null,
    starsGranted: row?.stars_granted ?? null,
    stars: DAILY_STARS,
  };
}

/**
 * One UTC-day grant. Amount is DAILY_STARS only.
 * Unique ledger id + unique (pubkey, day) make double-claim a no-op.
 */
export async function claimDailyStars(
  db: D1Database,
  opts: { pubkey: string; now: number },
): Promise<
  | { status: "ok"; stars: number; granted: number; dayUtc: string }
  | { status: "already_claimed"; stars: number; granted: number; dayUtc: string }
> {
  const granted = DAILY_STARS;
  if (!Number.isInteger(granted) || granted <= 0 || granted > 100) {
    throw new Error("daily_stars_misconfigured");
  }
  await ensureAccount(db, opts.pubkey, opts.now);
  const day = utcDay(opts.now);
  const existing = await starDailyStatus(db, opts.pubkey, opts.now);
  if (existing.claimed) {
    const w = await getWallet(db, opts.pubkey);
    return {
      status: "already_claimed",
      stars: w?.stars ?? 0,
      granted,
      dayUtc: day,
    };
  }

  try {
    await credit(db, {
      pubkey: opts.pubkey,
      currency: "stars",
      amount: granted,
      kind: "daily",
      refType: "star_daily",
      refId: day,
      ledgerId: `stardaily:${opts.pubkey}:${day}`,
      now: opts.now,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : "";
    if (!msg.includes("UNIQUE") && !msg.includes("constraint")) throw e;
  }

  try {
    await db
      .prepare(
        `INSERT INTO star_daily_claims (pubkey_hex, day_utc, claimed_at, stars_granted)
         VALUES (?, ?, ?, ?)`,
      )
      .bind(opts.pubkey, day, opts.now, granted)
      .run();
  } catch {
    const w = await getWallet(db, opts.pubkey);
    return {
      status: "already_claimed",
      stars: w?.stars ?? 0,
      granted,
      dayUtc: day,
    };
  }

  const w = await getWallet(db, opts.pubkey);
  return { status: "ok", stars: w?.stars ?? 0, granted, dayUtc: day };
}

export async function listStarInventory(db: D1Database, pubkey: string) {
  const { results } = await db
    .prepare(`SELECT sku_id, qty FROM star_inventory WHERE pubkey_hex = ? AND qty > 0`)
    .bind(pubkey)
    .all<{ sku_id: string; qty: number }>();
  return results ?? [];
}

export async function noteGiftSummary(db: D1Database, noteId: string) {
  const { results } = await db
    .prepare(
      `SELECT sku_id, COUNT(*) AS n FROM star_gifts WHERE note_id = ? GROUP BY sku_id`,
    )
    .bind(noteId)
    .all<{ sku_id: string; n: number }>();
  const bySku: Record<string, number> = {};
  let total = 0;
  for (const row of results ?? []) {
    const n = Number(row.n) || 0;
    bySku[row.sku_id] = n;
    total += n;
  }
  return { noteId, total, bySku };
}

export async function profileGiftSummary(db: D1Database, pubkey: string) {
  const { results } = await db
    .prepare(
      `SELECT sku_id, COUNT(*) AS n FROM star_gifts WHERE to_pubkey = ? GROUP BY sku_id`,
    )
    .bind(pubkey)
    .all<{ sku_id: string; n: number }>();
  const bySku: Record<string, number> = {};
  let total = 0;
  for (const row of results ?? []) {
    const n = Number(row.n) || 0;
    bySku[row.sku_id] = n;
    total += n;
  }
  return { pubkey, total, bySku };
}

/**
 * Debit stars at catalog price and increment inventory. Client cannot set price.
 */
export async function buyGift(
  db: D1Database,
  opts: { pubkey: string; skuId: string; ledgerId: string; now: number },
): Promise<
  | { status: "ok"; stars: number; qty: number; sku: GiftSku }
  | { status: "unknown_sku" }
  | { status: "insufficient"; stars: number; need: number }
  | { status: "duplicate"; stars: number; qty: number }
> {
  const sku = findGiftSku(opts.skuId);
  if (!sku) return { status: "unknown_sku" };
  await ensureAccount(db, opts.pubkey, opts.now);

  const debit = await db
    .prepare(
      `UPDATE accounts SET stars = stars - ?, updated_at = ?
       WHERE pubkey_hex = ? AND stars >= ?`,
    )
    .bind(sku.starPrice, opts.now, opts.pubkey, sku.starPrice)
    .run();
  const changed = (debit.meta as { changes?: number } | undefined)?.changes ?? 0;
  if (changed < 1) {
    const w = await getWallet(db, opts.pubkey);
    return { status: "insufficient", stars: w?.stars ?? 0, need: sku.starPrice };
  }

  const after = await getWallet(db, opts.pubkey);
  const balanceAfter = after?.stars ?? 0;

  try {
    await db.batch([
      db
        .prepare(
          `INSERT INTO star_inventory (pubkey_hex, sku_id, qty, updated_at)
           VALUES (?, ?, 1, ?)
           ON CONFLICT(pubkey_hex, sku_id) DO UPDATE SET qty = qty + 1, updated_at = excluded.updated_at`,
        )
        .bind(opts.pubkey, sku.id, opts.now),
      db
        .prepare(
          `INSERT INTO ledger (id, pubkey_hex, kind, currency, amount, balance_after, ref_type, ref_id, meta_json, created_at)
           VALUES (?, ?, 'purchase', 'stars', ?, ?, 'gift_sku', ?, ?, ?)`,
        )
        .bind(
          opts.ledgerId,
          opts.pubkey,
          sku.starPrice,
          balanceAfter,
          sku.id,
          JSON.stringify({ sku: sku.id, price: sku.starPrice }),
          opts.now,
        ),
    ]);
  } catch (e) {
    await db
      .prepare(`UPDATE accounts SET stars = stars + ?, updated_at = ? WHERE pubkey_hex = ?`)
      .bind(sku.starPrice, opts.now, opts.pubkey)
      .run();
    const msg = e instanceof Error ? e.message : "";
    if (msg.includes("UNIQUE") || msg.includes("constraint")) {
      const w = await getWallet(db, opts.pubkey);
      const inv = await db
        .prepare(`SELECT qty FROM star_inventory WHERE pubkey_hex = ? AND sku_id = ?`)
        .bind(opts.pubkey, sku.id)
        .first<{ qty: number }>();
      return { status: "duplicate", stars: w?.stars ?? 0, qty: inv?.qty ?? 0 };
    }
    throw e;
  }

  const inv = await db
    .prepare(`SELECT qty FROM star_inventory WHERE pubkey_hex = ? AND sku_id = ?`)
    .bind(opts.pubkey, sku.id)
    .first<{ qty: number }>();
  const w = await getWallet(db, opts.pubkey);
  return { status: "ok", stars: w?.stars ?? 0, qty: inv?.qty ?? 0, sku };
}

/**
 * Send one gift: consume inventory if present, otherwise debit catalog price.
 * Gift row id = ledgerId (idempotent).
 */
export async function sendGift(
  db: D1Database,
  opts: {
    fromPubkey: string;
    toPubkey: string;
    noteId: string | null;
    skuId: string;
    ledgerId: string;
    now: number;
  },
): Promise<
  | { status: "ok"; stars: number; sku: GiftSku; usedInventory: boolean }
  | { status: "unknown_sku" }
  | { status: "bad_recipient" }
  | { status: "self" }
  | { status: "insufficient"; stars: number; need: number }
  | { status: "duplicate"; stars: number }
> {
  const sku = findGiftSku(opts.skuId);
  if (!sku) return { status: "unknown_sku" };
  if (!isHex64(opts.toPubkey)) return { status: "bad_recipient" };
  if (opts.fromPubkey === opts.toPubkey) return { status: "self" };
  if (opts.noteId && !isHex64(opts.noteId)) return { status: "bad_recipient" };

  await ensureAccount(db, opts.fromPubkey, opts.now);
  await ensureAccount(db, opts.toPubkey, opts.now);

  const existing = await db
    .prepare(`SELECT 1 AS ok FROM star_gifts WHERE id = ?`)
    .bind(opts.ledgerId)
    .first();
  if (existing) {
    const w = await getWallet(db, opts.fromPubkey);
    return { status: "duplicate", stars: w?.stars ?? 0 };
  }

  const inv = await db
    .prepare(
      `UPDATE star_inventory SET qty = qty - 1, updated_at = ?
       WHERE pubkey_hex = ? AND sku_id = ? AND qty >= 1`,
    )
    .bind(opts.now, opts.fromPubkey, sku.id)
    .run();
  const usedInventory = ((inv.meta as { changes?: number } | undefined)?.changes ?? 0) >= 1;

  if (!usedInventory) {
    const debit = await db
      .prepare(
        `UPDATE accounts SET stars = stars - ?, updated_at = ?
         WHERE pubkey_hex = ? AND stars >= ?`,
      )
      .bind(sku.starPrice, opts.now, opts.fromPubkey, sku.starPrice)
      .run();
    const changed = (debit.meta as { changes?: number } | undefined)?.changes ?? 0;
    if (changed < 1) {
      const w = await getWallet(db, opts.fromPubkey);
      return { status: "insufficient", stars: w?.stars ?? 0, need: sku.starPrice };
    }
  }

  const after = await getWallet(db, opts.fromPubkey);
  const balanceAfter = after?.stars ?? 0;

  try {
    const stmts = [
      db
        .prepare(
          `INSERT INTO star_gifts (id, from_pubkey, to_pubkey, note_id, sku_id, stars_value, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)`,
        )
        .bind(
          opts.ledgerId,
          opts.fromPubkey,
          opts.toPubkey,
          opts.noteId,
          sku.id,
          sku.starPrice,
          opts.now,
        ),
    ];
    if (!usedInventory) {
      stmts.push(
        db
          .prepare(
            `INSERT INTO ledger (id, pubkey_hex, kind, currency, amount, balance_after, ref_type, ref_id, meta_json, created_at)
             VALUES (?, ?, 'gift', 'stars', ?, ?, 'gift_send', ?, ?, ?)`,
          )
          .bind(
            `giftpay:${opts.ledgerId}`,
            opts.fromPubkey,
            sku.starPrice,
            balanceAfter,
            sku.id,
            JSON.stringify({ to: opts.toPubkey, noteId: opts.noteId, sku: sku.id }),
            opts.now,
          ),
      );
    }
    await db.batch(stmts);
  } catch (e) {
    if (!usedInventory) {
      await db
        .prepare(`UPDATE accounts SET stars = stars + ?, updated_at = ? WHERE pubkey_hex = ?`)
        .bind(sku.starPrice, opts.now, opts.fromPubkey)
        .run();
    } else {
      await db
        .prepare(
          `INSERT INTO star_inventory (pubkey_hex, sku_id, qty, updated_at)
           VALUES (?, ?, 1, ?)
           ON CONFLICT(pubkey_hex, sku_id) DO UPDATE SET qty = qty + 1, updated_at = excluded.updated_at`,
        )
        .bind(opts.fromPubkey, sku.id, opts.now)
        .run();
    }
    const msg = e instanceof Error ? e.message : "";
    if (msg.includes("UNIQUE") || msg.includes("constraint")) {
      const w = await getWallet(db, opts.fromPubkey);
      return { status: "duplicate", stars: w?.stars ?? 0 };
    }
    throw e;
  }

  try {
    await credit(db, {
      pubkey: opts.toPubkey,
      currency: "stars",
      amount: sku.starPrice,
      kind: "gift_receive",
      refType: "gift_send",
      refId: opts.ledgerId,
      ledgerId: `giftrecv:${opts.ledgerId}`,
      now: opts.now,
      meta: { from: opts.fromPubkey, sku: sku.id, noteId: opts.noteId },
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : "";
    if (!msg.includes("UNIQUE") && !msg.includes("constraint")) {
      await db.prepare(`DELETE FROM star_gifts WHERE id = ?`).bind(opts.ledgerId).run();
      if (!usedInventory) {
        await db
          .prepare(`UPDATE accounts SET stars = stars + ?, updated_at = ? WHERE pubkey_hex = ?`)
          .bind(sku.starPrice, opts.now, opts.fromPubkey)
          .run();
      } else {
        await db
          .prepare(
            `INSERT INTO star_inventory (pubkey_hex, sku_id, qty, updated_at)
             VALUES (?, ?, 1, ?)
             ON CONFLICT(pubkey_hex, sku_id) DO UPDATE SET qty = qty + 1, updated_at = excluded.updated_at`,
          )
          .bind(opts.fromPubkey, sku.id, opts.now)
          .run();
      }
      throw e;
    }
  }

  const w = await getWallet(db, opts.fromPubkey);
  return { status: "ok", stars: w?.stars ?? 0, sku, usedInventory };
}

export async function createStarTopupOrder(
  db: D1Database,
  opts: { pubkey: string; packageId: string; now: number },
): Promise<
  | {
      status: "ok";
      orderId: string;
      packageId: string;
      stars: number;
      priceCents: number;
      currencyCode: string;
      checkoutEnabled: false;
    }
  | { status: "unknown_package" }
> {
  const pkg = findStarPackage(opts.packageId);
  if (!pkg) return { status: "unknown_package" };
  await ensureAccount(db, opts.pubkey, opts.now);
  const orderId = crypto.randomUUID();
  await db
    .prepare(
      `INSERT INTO topup_orders
       (id, pubkey_hex, package_id, gold, gems, stars, price_cents, currency_code, status, provider, provider_event_id, created_at, updated_at, paid_at)
       VALUES (?, ?, ?, 0, 0, ?, ?, ?, 'pending', NULL, NULL, ?, ?, NULL)`,
    )
    .bind(
      orderId,
      opts.pubkey,
      pkg.id,
      pkg.stars,
      pkg.priceCents,
      pkg.currencyCode,
      opts.now,
      opts.now,
    )
    .run();
  return {
    status: "ok",
    orderId,
    packageId: pkg.id,
    stars: pkg.stars,
    priceCents: pkg.priceCents,
    currencyCode: pkg.currencyCode,
    checkoutEnabled: false,
  };
}
