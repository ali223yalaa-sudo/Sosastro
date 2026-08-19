/**
 * Phase 4 — top-up packages & fulfillment.
 * Client never invents gold/gems: amounts come from this catalog only.
 * Credit happens only after signed webhook or admin fulfill of a pending order.
 */
import { credit, ensureAccount, getWallet } from "./ledger";

export type TopupPackage = {
  id: string;
  gold: number;
  gems: number;
  /** Display / future Stripe unit amount in minor units. */
  priceCents: number;
  currencyCode: string;
  label: string;
};

export const TOPUP_PACKAGES: TopupPackage[] = [
  { id: "gold_100", gold: 100, gems: 0, priceCents: 99, currencyCode: "USD", label: "100 Gold" },
  { id: "gold_500", gold: 500, gems: 0, priceCents: 399, currencyCode: "USD", label: "500 Gold" },
  { id: "gold_1200", gold: 1200, gems: 0, priceCents: 799, currencyCode: "USD", label: "1200 Gold" },
  { id: "gems_50", gold: 0, gems: 50, priceCents: 199, currencyCode: "USD", label: "50 Gems" },
  { id: "gems_200", gold: 0, gems: 200, priceCents: 599, currencyCode: "USD", label: "200 Gems" },
  {
    id: "bundle_starter",
    gold: 300,
    gems: 25,
    priceCents: 499,
    currencyCode: "USD",
    label: "Starter Bundle",
  },
];

export function findTopupPackage(id: string): TopupPackage | undefined {
  return TOPUP_PACKAGES.find((p) => p.id === id);
}

export function packagesCatalog() {
  return {
    packages: TOPUP_PACKAGES.map((p) => ({
      id: p.id,
      gold: p.gold,
      gems: p.gems,
      priceCents: p.priceCents,
      currencyCode: p.currencyCode,
      label: p.label,
    })),
    /** When false, UI shows packages but checkout waits for Stripe/Zain wiring. */
    checkoutEnabled: false,
    provider: "pending",
  };
}

export async function createTopupOrder(
  db: D1Database,
  opts: { pubkey: string; packageId: string; now: number },
): Promise<
  | {
      status: "ok";
      orderId: string;
      packageId: string;
      gold: number;
      gems: number;
      priceCents: number;
      currencyCode: string;
      checkoutEnabled: boolean;
    }
  | { status: "unknown_package" }
> {
  const pkg = findTopupPackage(opts.packageId);
  if (!pkg) return { status: "unknown_package" };
  await ensureAccount(db, opts.pubkey, opts.now);
  const orderId = crypto.randomUUID();
  await db
    .prepare(
      `INSERT INTO topup_orders
       (id, pubkey_hex, package_id, gold, gems, price_cents, currency_code, status, provider, provider_event_id, created_at, updated_at, paid_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', NULL, NULL, ?, ?, NULL)`,
    )
    .bind(
      orderId,
      opts.pubkey,
      pkg.id,
      pkg.gold,
      pkg.gems,
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
    gold: pkg.gold,
    gems: pkg.gems,
    priceCents: pkg.priceCents,
    currencyCode: pkg.currencyCode,
    checkoutEnabled: false,
  };
}

export async function fulfillTopupOrder(
  db: D1Database,
  opts: {
    orderId: string;
    providerEventId: string;
    provider: string;
    now: number;
    /** If set, must match order owner (webhook may omit and trust orderId alone). */
    expectedPubkey?: string;
  },
): Promise<
  | { status: "ok"; gold: number; gems: number; creditedGold: number; creditedGems: number }
  | { status: "already_paid"; gold: number; gems: number }
  | { status: "not_found" }
  | { status: "not_pending" }
  | { status: "pubkey_mismatch" }
> {
  const eventId = opts.providerEventId.trim().slice(0, 120);
  if (!eventId) return { status: "not_found" };

  const existingEvent = await db
    .prepare(`SELECT order_id FROM topup_events WHERE provider_event_id = ?`)
    .bind(eventId)
    .first<{ order_id: string }>();
  if (existingEvent) {
    const w = await getWallet(
      db,
      (
        await db
          .prepare(`SELECT pubkey_hex FROM topup_orders WHERE id = ?`)
          .bind(existingEvent.order_id)
          .first<{ pubkey_hex: string }>()
      )?.pubkey_hex ?? "",
    );
    return { status: "already_paid", gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
  }

  const order = await db
    .prepare(
      `SELECT id, pubkey_hex, gold, gems, stars, status FROM topup_orders WHERE id = ?`,
    )
    .bind(opts.orderId)
    .first<{
      id: string;
      pubkey_hex: string;
      gold: number;
      gems: number;
      stars: number;
      status: string;
    }>();
  if (!order) return { status: "not_found" };
  if (opts.expectedPubkey && opts.expectedPubkey !== order.pubkey_hex) {
    return { status: "pubkey_mismatch" };
  }
  if (order.status === "paid") {
    const w = await getWallet(db, order.pubkey_hex);
    return { status: "already_paid", gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
  }
  if (order.status !== "pending") return { status: "not_pending" };

  try {
    await db
      .prepare(`INSERT INTO topup_events (provider_event_id, order_id, created_at) VALUES (?, ?, ?)`)
      .bind(eventId, order.id, opts.now)
      .run();
  } catch {
    const w = await getWallet(db, order.pubkey_hex);
    return { status: "already_paid", gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
  }

  const marked = await db
    .prepare(
      `UPDATE topup_orders
       SET status = 'paid', provider = ?, provider_event_id = ?, paid_at = ?, updated_at = ?
       WHERE id = ? AND status = 'pending'`,
    )
    .bind(opts.provider.slice(0, 40), eventId, opts.now, opts.now, order.id)
    .run();
  const changed = (marked.meta as { changes?: number } | undefined)?.changes ?? 0;
  if (changed < 1) {
    const w = await getWallet(db, order.pubkey_hex);
    return { status: "already_paid", gold: w?.gold ?? 0, gems: w?.gems ?? 0 };
  }

  if (order.gold > 0) {
    await credit(db, {
      pubkey: order.pubkey_hex,
      currency: "gold",
      amount: order.gold,
      kind: "topup",
      refType: "topup_order",
      refId: order.id,
      ledgerId: `topup:gold:${order.id}`,
      now: opts.now,
      meta: { provider: opts.provider, providerEventId: eventId },
    });
  }
  if (order.gems > 0) {
    await credit(db, {
      pubkey: order.pubkey_hex,
      currency: "gems",
      amount: order.gems,
      kind: "topup",
      refType: "topup_order",
      refId: order.id,
      ledgerId: `topup:gems:${order.id}`,
      now: opts.now,
      meta: { provider: opts.provider, providerEventId: eventId },
    });
  }

  if (order.stars > 0) {
    await credit(db, {
      pubkey: order.pubkey_hex,
      currency: "stars",
      amount: order.stars,
      kind: "topup",
      refType: "topup_order",
      refId: order.id,
      ledgerId: `topup:stars:${order.id}`,
      now: opts.now,
      meta: { provider: opts.provider, providerEventId: eventId },
    });
  }

  const w = await getWallet(db, order.pubkey_hex);
  return {
    status: "ok",
    gold: w?.gold ?? 0,
    gems: w?.gems ?? 0,
    stars: w?.stars ?? 0,
    creditedGold: order.gold,
    creditedGems: order.gems,
    creditedStars: order.stars,
  };
}

/** HMAC-SHA256 hex of raw body — payment provider / ops shared secret. */
export async function hmacSha256Hex(secret: string, body: Uint8Array): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, body));
  return [...sig].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function timingSafeEqualHex(a: string, b: string): boolean {
  const aa = a.toLowerCase();
  const bb = b.toLowerCase();
  if (aa.length !== bb.length) return false;
  let acc = 0;
  for (let i = 0; i < aa.length; i++) acc |= aa.charCodeAt(i) ^ bb.charCodeAt(i);
  return acc === 0;
}
