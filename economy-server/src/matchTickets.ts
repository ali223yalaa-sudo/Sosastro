import {
  payoutForMatch,
  type MatchMode,
  type MatchStake,
} from "./matchFees";
import { credit, ensureAccount, getWallet } from "./ledger";

async function debitGold(
  db: D1Database,
  opts: {
    pubkey: string;
    amount: number;
    kind: string;
    refType: string;
    refId: string;
    ledgerId: string;
    now: number;
    meta?: unknown;
  },
): Promise<{ ok: true; gold: number; gems: number } | { ok: false; gold: number; gems: number }> {
  await ensureAccount(db, opts.pubkey, opts.now);
  const before = await getWallet(db, opts.pubkey);
  if (!before || before.gold < opts.amount) {
    return { ok: false, gold: before?.gold ?? 0, gems: before?.gems ?? 0 };
  }
  const balanceAfter = before.gold - opts.amount;
  const debit = await db
    .prepare(
      `UPDATE accounts SET gold = gold - ?, updated_at = ?
       WHERE pubkey_hex = ? AND gold >= ?`,
    )
    .bind(opts.amount, opts.now, opts.pubkey, opts.amount)
    .run();
  const changed = (debit.meta as { changes?: number } | undefined)?.changes ?? 0;
  if (changed < 1) {
    const w = await getWallet(db, opts.pubkey);
    return { ok: false, gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
  }
  await db
    .prepare(
      `INSERT INTO ledger (id, pubkey_hex, kind, currency, amount, balance_after, ref_type, ref_id, meta_json, created_at)
       VALUES (?, ?, ?, 'gold', ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
      opts.ledgerId,
      opts.pubkey,
      opts.kind,
      opts.amount,
      balanceAfter,
      opts.refType,
      opts.refId,
      opts.meta ? JSON.stringify(opts.meta) : null,
      opts.now,
    )
    .run();
  const w = await getWallet(db, opts.pubkey);
  return { ok: true, gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
}

export async function buyMatchTicket(
  db: D1Database,
  opts: { pubkey: string; mode: MatchMode; stake: MatchStake; now: number },
): Promise<
  | { status: "ok"; ticketId: string; gold: number; gems: number; amount: number; stake: number }
  | { status: "insufficient"; gold: number; gems: number; need: number }
> {
  const ticketId = crypto.randomUUID();
  const debited = await debitGold(db, {
    pubkey: opts.pubkey,
    amount: opts.stake,
    kind: "match_fee",
    refType: "match_ticket",
    refId: ticketId,
    ledgerId: `match_fee:${ticketId}`,
    now: opts.now,
    meta: { mode: opts.mode, stake: opts.stake },
  });
  if (!debited.ok) {
    return {
      status: "insufficient",
      gold: debited.gold,
      gems: debited.gems,
      need: opts.stake,
    };
  }
  try {
    await db
      .prepare(
        `INSERT INTO match_tickets (id, pubkey_hex, mode, amount, status, match_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'open', NULL, ?, ?)`,
      )
      .bind(ticketId, opts.pubkey, opts.mode, opts.stake, opts.now, opts.now)
      .run();
  } catch (e) {
    // Compensate debit so gold is never stranded without a ticket row.
    await credit(db, {
      pubkey: opts.pubkey,
      currency: "gold",
      amount: opts.stake,
      kind: "match_fee",
      refType: "match_ticket_refund",
      refId: ticketId,
      ledgerId: `match_refund_orphan:${ticketId}`,
      now: opts.now,
      meta: { reason: "ticket_insert_failed" },
    });
    throw e;
  }
  return {
    status: "ok",
    ticketId,
    gold: debited.gold,
    gems: debited.gems,
    amount: opts.stake,
    stake: opts.stake,
  };
}

/** Refund an unused (still open) ticket — leave lobby / search cancelled. */
export async function refundOpenTicket(
  db: D1Database,
  opts: { pubkey: string; ticketId: string; now: number },
): Promise<
  | { status: "ok"; gold: number; gems: number; amount: number }
  | { status: "not_open"; gold: number; gems: number }
  | { status: "not_found" }
> {
  const row = await db
    .prepare(
      `SELECT id, pubkey_hex, amount, status FROM match_tickets WHERE id = ?`,
    )
    .bind(opts.ticketId)
    .first<{ id: string; pubkey_hex: string; amount: number; status: string }>();
  if (!row) return { status: "not_found" };
  if (row.pubkey_hex !== opts.pubkey) return { status: "not_found" };

  const w0 = await getWallet(db, opts.pubkey);
  if (row.status !== "open") {
    return { status: "not_open", gold: w0?.gold ?? 0, gems: w0?.gems ?? 0 };
  }

  const marked = await db
    .prepare(
      `UPDATE match_tickets SET status = 'refunded', updated_at = ?
       WHERE id = ? AND status = 'open'`,
    )
    .bind(opts.now, opts.ticketId)
    .run();
  const changed = (marked.meta as { changes?: number } | undefined)?.changes ?? 0;
  if (changed < 1) {
    const w = await getWallet(db, opts.pubkey);
    return { status: "not_open", gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
  }

  const bal = await credit(db, {
    pubkey: opts.pubkey,
    currency: "gold",
    amount: row.amount,
    kind: "match_fee",
    refType: "match_ticket_refund",
    refId: opts.ticketId,
    ledgerId: `match_refund:${opts.ticketId}`,
    now: opts.now,
    meta: { ticketId: opts.ticketId },
  });
  return { status: "ok", gold: bal.gold, gems: bal.gems, amount: row.amount };
}

async function revertBinds(
  db: D1Database,
  matchId: string,
  ticketIds: string[],
  now: number,
): Promise<void> {
  for (const id of ticketIds) {
    await db
      .prepare(
        `UPDATE match_tickets SET status = 'open', match_id = NULL, updated_at = ?
         WHERE id = ? AND match_id = ? AND status = 'bound'`,
      )
      .bind(now, id, matchId)
      .run();
  }
}

export async function bindMatchTickets(
  db: D1Database,
  opts: {
    matchId: string;
    mode: MatchMode;
    tickets: Array<{ ticketId: string; pubkey: string }>;
    now: number;
  },
): Promise<{ ok: true; stake: number } | { ok: false; reason: string }> {
  if (!opts.matchId || opts.matchId.length > 80) return { ok: false, reason: "bad_match" };
  if (opts.tickets.length < 1) return { ok: false, reason: "no_tickets" };

  const existing = await db
    .prepare(`SELECT COUNT(*) AS c FROM match_tickets WHERE match_id = ? AND status = 'bound'`)
    .bind(opts.matchId)
    .first<{ c: number }>();
  if (existing && existing.c >= opts.tickets.length) {
    const sample = await db
      .prepare(`SELECT amount FROM match_tickets WHERE match_id = ? LIMIT 1`)
      .bind(opts.matchId)
      .first<{ amount: number }>();
    return { ok: true, stake: sample?.amount ?? 0 };
  }

  let stake: number | null = null;
  const newlyBound: string[] = [];
  for (const t of opts.tickets) {
    const pubkey = t.pubkey.toLowerCase();
    if (!/^[0-9a-f]{64}$/.test(pubkey)) {
      await revertBinds(db, opts.matchId, newlyBound, opts.now);
      return { ok: false, reason: "bad_pubkey" };
    }
    const row = await db
      .prepare(
        `SELECT id, pubkey_hex, mode, status, match_id, amount FROM match_tickets WHERE id = ?`,
      )
      .bind(t.ticketId)
      .first<{
        id: string;
        pubkey_hex: string;
        mode: string;
        status: string;
        match_id: string | null;
        amount: number;
      }>();
    if (!row) {
      await revertBinds(db, opts.matchId, newlyBound, opts.now);
      return { ok: false, reason: "ticket_missing" };
    }
    if (row.pubkey_hex !== pubkey) {
      await revertBinds(db, opts.matchId, newlyBound, opts.now);
      return { ok: false, reason: "ticket_owner" };
    }
    if (row.mode !== opts.mode) {
      await revertBinds(db, opts.matchId, newlyBound, opts.now);
      return { ok: false, reason: "ticket_mode" };
    }
    if (row.status === "bound" && row.match_id === opts.matchId) {
      stake = row.amount;
      continue;
    }
    if (row.status !== "open") {
      await revertBinds(db, opts.matchId, newlyBound, opts.now);
      return { ok: false, reason: "ticket_used" };
    }
    if (stake == null) stake = row.amount;
    else if (stake !== row.amount) {
      await revertBinds(db, opts.matchId, newlyBound, opts.now);
      return { ok: false, reason: "stake_mismatch" };
    }

    const updated = await db
      .prepare(
        `UPDATE match_tickets SET status = 'bound', match_id = ?, updated_at = ? WHERE id = ? AND status = 'open'`,
      )
      .bind(opts.matchId, opts.now, t.ticketId)
      .run();
    const changed = (updated.meta as { changes?: number } | undefined)?.changes ?? 0;
    if (changed < 1) {
      await revertBinds(db, opts.matchId, newlyBound, opts.now);
      return { ok: false, reason: "ticket_used" };
    }
    newlyBound.push(t.ticketId);
  }
  return { ok: true, stake: stake ?? 0 };
}

export async function payoutMatchWinner(
  db: D1Database,
  opts: {
    matchId: string;
    mode: MatchMode;
    winnerPubkey: string | null;
    winnerIsBot: boolean;
    now: number;
  },
): Promise<
  | { status: "ok"; paid: number; gold?: number; gems?: number }
  | { status: "already_paid"; paid: number }
  | { status: "house_keeps"; paid: 0 }
> {
  const prior = await db
    .prepare(`SELECT amount FROM match_payouts WHERE match_id = ?`)
    .bind(opts.matchId)
    .first<{ amount: number }>();
  if (prior) {
    return { status: "already_paid", paid: prior.amount };
  }

  const tickets = await db
    .prepare(
      `SELECT pubkey_hex, mode, amount, status FROM match_tickets
       WHERE match_id = ? AND status IN ('bound','lost')`,
    )
    .bind(opts.matchId)
    .all<{ pubkey_hex: string; mode: string; amount: number; status: string }>();
  const bound = (tickets.results ?? []).filter((t) => t.status === "bound" || t.status === "lost");
  const humanTickets = (tickets.results ?? []).filter((t) => t.status === "bound");
  // Prefer still-bound at settle; if already marked lost from prior attempt, use all.
  const source = humanTickets.length > 0 ? humanTickets : bound;
  if (source.length < 1) {
    await db
      .prepare(
        `INSERT INTO match_payouts (match_id, mode, winner_pubkey, amount, created_at)
         VALUES (?, ?, NULL, 0, ?)`,
      )
      .bind(opts.matchId, opts.mode, opts.now)
      .run()
      .catch(() => undefined);
    return { status: "house_keeps", paid: 0 };
  }

  const modeFromTickets = (source[0]!.mode as MatchMode) || opts.mode;
  const stake = source[0]!.amount;
  const humanCount = new Set(source.map((t) => t.pubkey_hex)).size;
  const payout = payoutForMatch({
    mode: modeFromTickets,
    stake,
    humanTicketCount: humanCount,
  });

  if (opts.winnerIsBot || !opts.winnerPubkey || payout <= 0) {
    await db
      .prepare(
        `INSERT INTO match_payouts (match_id, mode, winner_pubkey, amount, created_at)
         VALUES (?, ?, NULL, 0, ?)`,
      )
      .bind(opts.matchId, modeFromTickets, opts.now)
      .run();
    await db
      .prepare(
        `UPDATE match_tickets SET status = 'lost', updated_at = ? WHERE match_id = ? AND status = 'bound'`,
      )
      .bind(opts.now, opts.matchId)
      .run();
    return { status: "house_keeps", paid: 0 };
  }

  const winner = opts.winnerPubkey.toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(winner)) {
    return { status: "house_keeps", paid: 0 };
  }

  // Defense in depth: winner must own a bound ticket for this match.
  const owns = source.some((t) => t.pubkey_hex === winner);
  if (!owns) {
    await db
      .prepare(
        `INSERT INTO match_payouts (match_id, mode, winner_pubkey, amount, created_at)
         VALUES (?, ?, NULL, 0, ?)`,
      )
      .bind(opts.matchId, modeFromTickets, opts.now)
      .run()
      .catch(() => undefined);
    await db
      .prepare(
        `UPDATE match_tickets SET status = 'lost', updated_at = ? WHERE match_id = ? AND status = 'bound'`,
      )
      .bind(opts.now, opts.matchId)
      .run();
    return { status: "house_keeps", paid: 0 };
  }

  try {
    await db
      .prepare(
        `INSERT INTO match_payouts (match_id, mode, winner_pubkey, amount, created_at)
         VALUES (?, ?, ?, ?, ?)`,
      )
      .bind(opts.matchId, modeFromTickets, winner, payout, opts.now)
      .run();
  } catch {
    const again = await db
      .prepare(`SELECT amount FROM match_payouts WHERE match_id = ?`)
      .bind(opts.matchId)
      .first<{ amount: number }>();
    return { status: "already_paid", paid: again?.amount ?? 0 };
  }

  const bal = await credit(db, {
    pubkey: winner,
    currency: "gold",
    amount: payout,
    kind: "match_reward",
    refType: "match",
    refId: opts.matchId,
    ledgerId: `match_reward:${opts.matchId}`,
    now: opts.now,
    meta: { mode: modeFromTickets, stake, payout, humanTicketCount: humanCount },
  });

  await db
    .prepare(
      `UPDATE match_tickets SET status = CASE WHEN pubkey_hex = ? THEN 'bound' ELSE 'lost' END,
         updated_at = ? WHERE match_id = ? AND status = 'bound'`,
    )
    .bind(winner, opts.now, opts.matchId)
    .run();

  return { status: "ok", paid: payout, gold: bal.gold, gems: bal.gems };
}
