import { findSku, freeSkus, utcDay, type ItemKind } from "./catalog";

export type Currency = "gold" | "gems" | "stars";

export type Env = {
  DB: D1Database;
  PUBLIC_BASE_URL: string;
  /** Optional shared secret for admin credit (ops only — never in APK). */
  ADMIN_SECRET?: string;
  /** Shared with dice-server for bind tickets + winner payout (never in APK). */
  GAME_WORKER_SECRET?: string;
  /** HMAC secret for POST /v1/topup/webhook body signature (never in APK). */
  PAYMENT_WEBHOOK_SECRET?: string;
};

export async function ensureAccount(db: D1Database, pubkey: string, now: number): Promise<void> {
  await db
    .prepare(
      `INSERT INTO accounts (pubkey_hex, gold, gems, stars, created_at, updated_at)
       VALUES (?, 0, 0, 0, ?, ?)
       ON CONFLICT(pubkey_hex) DO NOTHING`,
    )
    .bind(pubkey, now, now)
    .run();
}

export async function getWallet(db: D1Database, pubkey: string) {
  const row = await db
    .prepare(`SELECT pubkey_hex, gold, gems, stars, created_at, updated_at FROM accounts WHERE pubkey_hex = ?`)
    .bind(pubkey)
    .first<{
      pubkey_hex: string;
      gold: number;
      gems: number;
      stars: number;
      created_at: number;
      updated_at: number;
    }>();
  return row;
}

export async function listOwned(db: D1Database, pubkey: string) {
  const { results } = await db
    .prepare(
      `SELECT item_kind, item_id, acquired_at FROM ownership WHERE pubkey_hex = ? ORDER BY acquired_at DESC`,
    )
    .bind(pubkey)
    .all<{ item_kind: string; item_id: string; acquired_at: number }>();
  return results ?? [];
}

export async function isOwned(
  db: D1Database,
  pubkey: string,
  kind: string,
  id: string,
): Promise<boolean> {
  const row = await db
    .prepare(
      `SELECT 1 AS ok FROM ownership WHERE pubkey_hex = ? AND item_kind = ? AND item_id = ? LIMIT 1`,
    )
    .bind(pubkey, kind, id)
    .first<{ ok: number }>();
  return !!row;
}

export async function listLedger(db: D1Database, pubkey: string, limit: number) {
  const { results } = await db
    .prepare(
      `SELECT id, kind, currency, amount, balance_after, ref_type, ref_id, created_at
       FROM ledger WHERE pubkey_hex = ? ORDER BY created_at DESC LIMIT ?`,
    )
    .bind(pubkey, limit)
    .all();
  return results ?? [];
}

/** Grant free starter cosmetics (idempotent). */
export async function grantFreeOwnership(
  db: D1Database,
  pubkey: string,
  now: number,
): Promise<void> {
  await ensureAccount(db, pubkey, now);
  const stmts = freeSkus().map((s) =>
    db
      .prepare(
        `INSERT INTO ownership (pubkey_hex, item_kind, item_id, acquired_at)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(pubkey_hex, item_kind, item_id) DO NOTHING`,
      )
      .bind(pubkey, s.kind, s.id, now),
  );
  if (stmts.length) await db.batch(stmts);
}

/**
 * Credit currency with append-only ledger (atomic).
 */
export async function credit(
  db: D1Database,
  opts: {
    pubkey: string;
    currency: Currency;
    amount: number;
    kind: string;
    refType?: string;
    refId?: string;
    meta?: unknown;
    ledgerId: string;
    now: number;
  },
): Promise<{ gold: number; gems: number; stars: number }> {
  if (opts.amount <= 0) throw new Error("amount_must_be_positive");
  await ensureAccount(db, opts.pubkey, opts.now);

  const col =
    opts.currency === "gold" ? "gold" : opts.currency === "gems" ? "gems" : "stars";
  const before = await getWallet(db, opts.pubkey);
  if (!before) throw new Error("account_missing");
  const prev =
    opts.currency === "gold" ? before.gold : opts.currency === "gems" ? before.gems : before.stars ?? 0;
  const balanceAfter = prev + opts.amount;

  await db.batch([
    db
      .prepare(`UPDATE accounts SET ${col} = ${col} + ?, updated_at = ? WHERE pubkey_hex = ?`)
      .bind(opts.amount, opts.now, opts.pubkey),
    db
      .prepare(
        `INSERT INTO ledger (id, pubkey_hex, kind, currency, amount, balance_after, ref_type, ref_id, meta_json, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .bind(
        opts.ledgerId,
        opts.pubkey,
        opts.kind,
        opts.currency,
        opts.amount,
        balanceAfter,
        opts.refType ?? null,
        opts.refId ?? null,
        opts.meta ? JSON.stringify(opts.meta) : null,
        opts.now,
      ),
  ]);

  const w = await getWallet(db, opts.pubkey);
  if (!w) throw new Error("account_missing");
  return { gold: w.gold, gems: w.gems, stars: w.stars ?? 0 };
}

/**
 * Atomic purchase: debit + ownership + ledger. Price from server catalog only.
 */
export async function purchase(
  db: D1Database,
  opts: {
    pubkey: string;
    kind: ItemKind;
    id: string;
    ledgerId: string;
    now: number;
  },
): Promise<
  | { status: "ok"; gold: number; gems: number; owned: true }
  | { status: "already_owned"; gold: number; gems: number }
  | { status: "insufficient"; gold: number; gems: number; need: number; currency: Currency }
  | { status: "unknown_sku" }
> {
  const sku = findSku(opts.kind, opts.id);
  if (!sku) return { status: "unknown_sku" };

  await ensureAccount(db, opts.pubkey, opts.now);
  await grantFreeOwnership(db, opts.pubkey, opts.now);

  if (await isOwned(db, opts.pubkey, opts.kind, opts.id)) {
    const w = await getWallet(db, opts.pubkey);
    return { status: "already_owned", gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
  }

  const before = await getWallet(db, opts.pubkey);
  if (!before) throw new Error("account_missing");

  if (sku.price === 0) {
    await db
      .prepare(
        `INSERT INTO ownership (pubkey_hex, item_kind, item_id, acquired_at) VALUES (?, ?, ?, ?)`,
      )
      .bind(opts.pubkey, opts.kind, opts.id, opts.now)
      .run();
    return { status: "ok", gold: before.gold, gems: before.gems, owned: true };
  }

  const bal = sku.currency === "gold" ? before.gold : before.gems;
  if (bal < sku.price) {
    return {
      status: "insufficient",
      gold: before.gold,
      gems: before.gems,
      need: sku.price,
      currency: sku.currency,
    };
  }

  const col = sku.currency === "gold" ? "gold" : "gems";
  const balanceAfter = bal - sku.price;

  // Debit first with balance guard — changes===0 means race / insufficient.
  const debit = await db
    .prepare(
      `UPDATE accounts SET ${col} = ${col} - ?, updated_at = ?
       WHERE pubkey_hex = ? AND ${col} >= ?`,
    )
    .bind(sku.price, opts.now, opts.pubkey, sku.price)
    .run();
  const changed = (debit.meta as { changes?: number } | undefined)?.changes ?? 0;
  if (changed < 1) {
    const w = await getWallet(db, opts.pubkey);
    return {
      status: "insufficient",
      gold: w?.gold ?? 0,
      gems: w?.gems ?? 0,
      need: sku.price,
      currency: sku.currency,
    };
  }

  try {
    await db.batch([
      db
        .prepare(
          `INSERT INTO ownership (pubkey_hex, item_kind, item_id, acquired_at) VALUES (?, ?, ?, ?)`,
        )
        .bind(opts.pubkey, opts.kind, opts.id, opts.now),
      db
        .prepare(
          `INSERT INTO ledger (id, pubkey_hex, kind, currency, amount, balance_after, ref_type, ref_id, meta_json, created_at)
           VALUES (?, ?, 'purchase', ?, ?, ?, 'sku', ?, ?, ?)`,
        )
        .bind(
          opts.ledgerId,
          opts.pubkey,
          sku.currency,
          sku.price,
          balanceAfter,
          `${opts.kind}:${opts.id}`,
          JSON.stringify({ kind: opts.kind, id: opts.id, price: sku.price }),
          opts.now,
        ),
    ]);
  } catch (e) {
    // Roll back debit if ownership/ledger failed (duplicate race).
    await db
      .prepare(`UPDATE accounts SET ${col} = ${col} + ?, updated_at = ? WHERE pubkey_hex = ?`)
      .bind(sku.price, opts.now, opts.pubkey)
      .run();
    const msg = e instanceof Error ? e.message : "";
    if (msg.includes("UNIQUE") || msg.includes("constraint")) {
      const w = await getWallet(db, opts.pubkey);
      return { status: "already_owned", gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
    }
    throw e;
  }

  const after = await getWallet(db, opts.pubkey);
  if (!after) throw new Error("account_missing");
  return { status: "ok", gold: after.gold, gems: after.gems, owned: true };
}

export async function dailyStatus(db: D1Database, pubkey: string, now: number) {
  const day = utcDay(now);
  const row = await db
    .prepare(`SELECT claimed_at, gold_granted, gems_granted FROM daily_claims WHERE pubkey_hex = ? AND day_utc = ?`)
    .bind(pubkey, day)
    .first<{ claimed_at: number; gold_granted: number; gems_granted: number }>();
  return {
    dayUtc: day,
    claimed: !!row,
    claimedAt: row?.claimed_at ?? null,
    goldGranted: row?.gold_granted ?? null,
    gemsGranted: row?.gems_granted ?? null,
  };
}

export async function claimDaily(
  db: D1Database,
  opts: {
    pubkey: string;
    gold: number;
    gems: number;
    now: number;
  },
): Promise<
  | { status: "ok"; gold: number; gems: number; dayUtc: string }
  | { status: "already_claimed"; gold: number; gems: number; dayUtc: string }
> {
  await ensureAccount(db, opts.pubkey, opts.now);
  const day = utcDay(opts.now);
  const existing = await dailyStatus(db, opts.pubkey, opts.now);
  if (existing.claimed) {
    const w = await getWallet(db, opts.pubkey);
    return {
      status: "already_claimed",
      gold: w?.gold ?? 0,
      gems: w?.gems ?? 0,
      dayUtc: day,
    };
  }

  // Credit first with unique ledger ids (idempotent). Then insert claim row.
  // Prevents "claimed but never credited" if the process dies mid-flow.
  const creditOne = async (currency: "gold" | "gems", amount: number) => {
    if (amount <= 0) return;
    try {
      await credit(db, {
        pubkey: opts.pubkey,
        currency,
        amount,
        kind: "daily",
        refType: "daily",
        refId: day,
        ledgerId: `daily:${currency}:${opts.pubkey}:${day}`,
        now: opts.now,
      });
    } catch (e) {
      const msg = e instanceof Error ? e.message : "";
      if (!msg.includes("UNIQUE") && !msg.includes("constraint")) throw e;
      // Already granted this currency for the day — OK.
    }
  };
  await creditOne("gold", opts.gold);
  await creditOne("gems", opts.gems);

  try {
    await db
      .prepare(
        `INSERT INTO daily_claims (pubkey_hex, day_utc, claimed_at, gold_granted, gems_granted)
         VALUES (?, ?, ?, ?, ?)`,
      )
      .bind(opts.pubkey, day, opts.now, opts.gold, opts.gems)
      .run();
  } catch {
    const w = await getWallet(db, opts.pubkey);
    return {
      status: "already_claimed",
      gold: w?.gold ?? 0,
      gems: w?.gems ?? 0,
      dayUtc: day,
    };
  }

  const w = await getWallet(db, opts.pubkey);
  return { status: "ok", gold: w?.gold ?? 0, gems: w?.gems ?? 0, dayUtc: day };
}

export async function consumeReplay(
  db: D1Database,
  eventId: string,
  pubkey: string,
  expiresAt: number,
): Promise<boolean> {
  await db.prepare(`DELETE FROM auth_replay WHERE expires_at < ?`).bind(Math.floor(Date.now() / 1000)).run();

  try {
    await db
      .prepare(`INSERT INTO auth_replay (event_id, pubkey_hex, expires_at) VALUES (?, ?, ?)`)
      .bind(eventId, pubkey, expiresAt)
      .run();
    return true;
  } catch {
    return false;
  }
}
