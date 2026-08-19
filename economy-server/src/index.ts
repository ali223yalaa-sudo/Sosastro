/**
 * Isolated economy API — does NOT touch Ludo match/room Durable Objects.
 *
 * Phase 0–2 security:
 * - NIP-98 on every private route
 * - Catalog prices only on server
 * - Purchase debits with balance guard + ledger
 * - Daily claim once per UTC day (survives app wipe)
 */
import { CATALOG, DAILY_GEMS, DAILY_GOLD, type ItemKind } from "./catalog";
import { DAILY_STARS, starsCatalogPublic } from "./starsCatalog";
import {
  buyGift,
  claimDailyStars,
  createStarTopupOrder,
  isHex64,
  listStarInventory,
  noteGiftSummary,
  profileGiftSummary,
  sendGift,
  starDailyStatus,
} from "./starsOps";
import { feesCatalog, parseMatchMode, parseStake } from "./matchFees";
import { bindMatchTickets, buyMatchTicket, payoutMatchWinner, refundOpenTicket } from "./matchTickets";
import {
  claimDaily,
  consumeReplay,
  credit,
  dailyStatus,
  ensureAccount,
  getWallet,
  grantFreeOwnership,
  listLedger,
  listOwned,
  purchase,
  type Env,
} from "./ledger";
import { verifyNip98Header } from "./nip98";
import { hitRateLimit } from "./rateLimit";
import {
  awardXp,
  getProgress,
  XP_REWARDS,
} from "./progression";
import { mintTeamSessionToken } from "./teamSession";
import { heartbeatPresence, queryPresence } from "./presence";
import {
  createTopupOrder,
  fulfillTopupOrder,
  hmacSha256Hex,
  packagesCatalog,
  timingSafeEqualHex,
} from "./topup";
import { countryFromRequest, getGeoConsent, saveGeoConsent } from "./geo";
import { getUserLocation, listNearbyAuthors, saveUserLocation } from "./location";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
} as const;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function secretEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ba = enc.encode(a);
  const bb = enc.encode(b);
  if (ba.byteLength !== bb.byteLength) {
    let acc = ba.byteLength ^ bb.byteLength;
    const n = Math.max(ba.byteLength, bb.byteLength);
    for (let i = 0; i < n; i++) acc |= (ba[i] ?? 0) ^ (bb[i] ?? 0);
    return acc === 0;
  }
  let acc = 0;
  for (let i = 0; i < ba.byteLength; i++) acc |= ba[i]! ^ bb[i]!;
  return acc === 0;
}

async function requireUser(
  request: Request,
  env: Env,
  body: Uint8Array | null,
): Promise<{ pubkey: string } | Response> {
  if (!env.PUBLIC_BASE_URL) {
    return json({ error: "Misconfigured", reason: "missing_public_base_url" }, 500);
  }

  const urlForAuth = new URL(request.url);
  const signedTarget = `${env.PUBLIC_BASE_URL.replace(/\/$/, "")}${urlForAuth.pathname}${urlForAuth.search}`;

  const auth = verifyNip98Header({
    authorization: request.headers.get("Authorization"),
    method: request.method,
    url: signedTarget,
    body,
  });
  if (!auth.ok) {
    return json({ error: "Unauthorized", reason: auth.reason }, 401);
  }

  const now = Math.floor(Date.now() / 1000);
  const fresh = await consumeReplay(env.DB, auth.eventId, auth.pubkey, now + 120);
  if (!fresh) {
    return json({ error: "Unauthorized", reason: "replay" }, 401);
  }

  const rl = await hitRateLimit(env.DB, `pk:${auth.pubkey}`, 60, 60, now);
  if (!rl.allowed) {
    return json({ error: "RateLimited" }, 429);
  }

  return { pubkey: auth.pubkey };
}

function parseKind(raw: unknown): ItemKind | null {
  return raw === "dice" || raw === "piece" || raw === "board" ? raw : null;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          ...JSON_HEADERS,
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "GET, POST, OPTIONS",
          "access-control-allow-headers":
            "authorization, content-type, x-admin-secret, x-idempotency-key, x-payment-signature",
        },
      });
    }

    if (request.method === "GET" && url.pathname === "/health") {
      return json({
        ok: true,
        service: "amethyst-ludo-economy",
        isolatedFromLudoGameRooms: true,
        phase: 4,
      });
    }

    if (request.method === "GET" && url.pathname === "/v1/match/fees") {
      return json(feesCatalog());
    }

    // Public catalog (prices authoritative on server; signing not required to browse).
    if (request.method === "GET" && url.pathname === "/v1/catalog") {
      return json({
        items: CATALOG,
        daily: { gold: DAILY_GOLD, gems: DAILY_GEMS },
      });
    }

    if (request.method === "GET" && url.pathname === "/v1/stars/catalog") {
      return json({ ok: true, ...starsCatalogPublic() });
    }

    const noteGiftMatch = url.pathname.match(/^\/v1\/stars\/gifts\/note\/([0-9a-f]{64})$/);
    if (request.method === "GET" && noteGiftMatch) {
      const now = Math.floor(Date.now() / 1000);
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      const rl = await hitRateLimit(env.DB, `starsnote:${ip}`, 120, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      const summary = await noteGiftSummary(env.DB, noteGiftMatch[1]);
      return json({ ok: true, ...summary });
    }

    const profileGiftMatch = url.pathname.match(/^\/v1\/stars\/gifts\/profile\/([0-9a-f]{64})$/);
    if (request.method === "GET" && profileGiftMatch) {
      const now = Math.floor(Date.now() / 1000);
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      const rl = await hitRateLimit(env.DB, `starsprofile:${ip}`, 120, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      const summary = await profileGiftSummary(env.DB, profileGiftMatch[1]);
      return json({ ok: true, ...summary });
    }

    if (request.method === "POST" && url.pathname === "/v1/stars/buy") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      let body: { skuId?: string; idempotencyKey?: string };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const skuId = (body.skuId || "").trim();
      const idem =
        (request.headers.get("x-idempotency-key") || body.idempotencyKey || "").trim();
      if (!skuId || skuId.length > 32) return json({ error: "BadSku" }, 400);
      if (!idem || idem.length > 80) return json({ error: "BadIdempotency" }, 400);
      const now = Math.floor(Date.now() / 1000);
      try {
        const result = await buyGift(env.DB, {
          pubkey: auth.pubkey,
          skuId,
          ledgerId: `starbuy:${auth.pubkey}:${idem}`,
          now,
        });
        if (result.status === "unknown_sku") return json({ error: "UnknownSku" }, 404);
        if (result.status === "insufficient") {
          return json({ error: "Insufficient", stars: result.stars, need: result.need }, 402);
        }
        if (result.status === "duplicate") {
          return json({ ok: true, duplicate: true, stars: result.stars, qty: result.qty });
        }
        return json({
          ok: true,
          stars: result.stars,
          qty: result.qty,
          skuId: result.sku.id,
          starPrice: result.sku.starPrice,
        });
      } catch (e) {
        const msg = e instanceof Error ? e.message : "error";
        return json({ error: "BuyFailed", detail: msg }, 500);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/stars/send") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      let body: { skuId?: string; toPubkey?: string; noteId?: string; idempotencyKey?: string };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const skuId = (body.skuId || "").trim();
      const toPubkey = (body.toPubkey || "").trim().toLowerCase();
      const noteId = (body.noteId || "").trim().toLowerCase() || null;
      const idem =
        (request.headers.get("x-idempotency-key") || body.idempotencyKey || "").trim();
      if (!skuId || skuId.length > 32) return json({ error: "BadSku" }, 400);
      if (!isHex64(toPubkey)) return json({ error: "BadRecipient" }, 400);
      if (noteId && !isHex64(noteId)) return json({ error: "BadNote" }, 400);
      if (!idem || idem.length > 80) return json({ error: "BadIdempotency" }, 400);
      const now = Math.floor(Date.now() / 1000);
      try {
        const result = await sendGift(env.DB, {
          fromPubkey: auth.pubkey,
          toPubkey,
          noteId,
          skuId,
          ledgerId: `starsend:${auth.pubkey}:${idem}`,
          now,
        });
        if (result.status === "unknown_sku") return json({ error: "UnknownSku" }, 404);
        if (result.status === "self") return json({ error: "SelfGift" }, 400);
        if (result.status === "bad_recipient") return json({ error: "BadRecipient" }, 400);
        if (result.status === "insufficient") {
          return json({ error: "Insufficient", stars: result.stars, need: result.need }, 402);
        }
        if (result.status === "duplicate") {
          return json({ ok: true, duplicate: true, stars: result.stars });
        }
        return json({
          ok: true,
          stars: result.stars,
          skuId: result.sku.id,
          usedInventory: result.usedInventory,
        });
      } catch (e) {
        const msg = e instanceof Error ? e.message : "error";
        return json({ error: "SendFailed", detail: msg }, 500);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/stars/topup/create") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      let body: { packageId?: string };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const packageId = (body.packageId || "").trim();
      if (!packageId || packageId.length > 40) return json({ error: "BadPackage" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const result = await createStarTopupOrder(env.DB, {
        pubkey: auth.pubkey,
        packageId,
        now,
      });
      if (result.status === "unknown_package") return json({ error: "UnknownPackage" }, 404);
      return json({
        ok: true,
        ...result,
        // Payment gateway is not wired; order stays pending until webhook/admin.
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/stars/daily/claim") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes.byteLength ? bodyBytes : null);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const rl = await hitRateLimit(env.DB, `stardaily:${auth.pubkey}`, 8, 3600, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      try {
        const result = await claimDailyStars(env.DB, { pubkey: auth.pubkey, now });
        if (result.status === "already_claimed") {
          return json(
            {
              error: "AlreadyClaimed",
              stars: result.stars,
              granted: result.granted,
              dayUtc: result.dayUtc,
              reward: { stars: DAILY_STARS },
            },
            409,
          );
        }
        return json({
          ok: true,
          stars: result.stars,
          granted: result.granted,
          dayUtc: result.dayUtc,
          reward: { stars: DAILY_STARS },
        });
      } catch (e) {
        const msg = e instanceof Error ? e.message : "error";
        return json({ error: "DailyClaimFailed", detail: msg }, 500);
      }
    }

    if (request.method === "GET" && url.pathname === "/v1/wallet") {
      const auth = await requireUser(request, env, null);
      if (auth instanceof Response) return auth;

      const now = Math.floor(Date.now() / 1000);
      await ensureAccount(env.DB, auth.pubkey, now);
      await grantFreeOwnership(env.DB, auth.pubkey, now);
      const wallet = await getWallet(env.DB, auth.pubkey);
      const owned = await listOwned(env.DB, auth.pubkey);
      const inventory = await listStarInventory(env.DB, auth.pubkey);
      const daily = await dailyStatus(env.DB, auth.pubkey, now);
      const starDaily = await starDailyStatus(env.DB, auth.pubkey, now);
      return json({
        pubkey: auth.pubkey,
        gold: wallet?.gold ?? 0,
        gems: wallet?.gems ?? 0,
        stars: wallet?.stars ?? 0,
        inventory: inventory.map((i) => ({ skuId: i.sku_id, qty: i.qty })),
        owned: owned.map((o) => ({
          kind: o.item_kind,
          id: o.item_id,
          acquiredAt: o.acquired_at,
        })),
        daily,
        starDaily,
        updatedAt: wallet?.updated_at ?? now,
      });
    }

    if (request.method === "GET" && url.pathname === "/v1/ledger") {
      const auth = await requireUser(request, env, null);
      if (auth instanceof Response) return auth;
      const limit = Math.min(50, Math.max(1, Number(url.searchParams.get("limit") || 20)));
      const now = Math.floor(Date.now() / 1000);
      await ensureAccount(env.DB, auth.pubkey, now);
      const rows = await listLedger(env.DB, auth.pubkey, limit);
      return json({ pubkey: auth.pubkey, entries: rows });
    }

    if (request.method === "GET" && url.pathname === "/v1/daily") {
      const auth = await requireUser(request, env, null);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      await ensureAccount(env.DB, auth.pubkey, now);
      const daily = await dailyStatus(env.DB, auth.pubkey, now);
      return json({
        ...daily,
        reward: { gold: DAILY_GOLD, gems: DAILY_GEMS },
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/daily/claim") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes.byteLength ? bodyBytes : null);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const result = await claimDaily(env.DB, {
        pubkey: auth.pubkey,
        gold: DAILY_GOLD,
        gems: DAILY_GEMS,
        now,
      });
      if (result.status === "already_claimed") {
        return json({ error: "AlreadyClaimed", ...result }, 409);
      }
      return json({ ok: true, ...result, reward: { gold: DAILY_GOLD, gems: DAILY_GEMS } });
    }

    if (request.method === "POST" && url.pathname === "/v1/purchase") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;

      let body: { kind?: string; id?: string; idempotencyKey?: string };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const kind = parseKind(body.kind);
      const id = (body.id || "").trim();
      if (!kind || !id || id.length > 64) return json({ error: "BadSku" }, 400);

      const idem =
        (request.headers.get("x-idempotency-key") || body.idempotencyKey || "").trim() ||
        crypto.randomUUID();
      if (idem.length > 80) return json({ error: "BadIdempotency" }, 400);

      const now = Math.floor(Date.now() / 1000);
      try {
        const result = await purchase(env.DB, {
          pubkey: auth.pubkey,
          kind,
          id,
          ledgerId: `buy:${idem}`,
          now,
        });
        if (result.status === "unknown_sku") return json({ error: "UnknownSku" }, 404);
        if (result.status === "already_owned") {
          return json({ ok: true, alreadyOwned: true, gold: result.gold, gems: result.gems });
        }
        if (result.status === "insufficient") {
          return json(
            {
              error: "InsufficientFunds",
              gold: result.gold,
              gems: result.gems,
              need: result.need,
              currency: result.currency,
            },
            402,
          );
        }
        return json({
          ok: true,
          alreadyOwned: false,
          gold: result.gold,
          gems: result.gems,
          kind,
          id,
          idempotencyKey: idem,
        });
      } catch (e) {
        const msg = e instanceof Error ? e.message : "error";
        if (msg.includes("UNIQUE") || msg.includes("constraint")) {
          return json({ error: "DuplicateIdempotency" }, 409);
        }
        return json({ error: "PurchaseFailed", detail: msg }, 500);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/match/ticket") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      let body: { mode?: string; stake?: number };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const mode = parseMatchMode(body.mode);
      const stake = parseStake(body.stake);
      if (!mode) return json({ error: "BadMode" }, 400);
      if (!stake) return json({ error: "BadStake" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const result = await buyMatchTicket(env.DB, { pubkey: auth.pubkey, mode, stake, now });
      if (result.status === "insufficient") {
        return json(
          {
            error: "InsufficientFunds",
            gold: result.gold,
            gems: result.gems,
            need: result.need,
            currency: "gold",
          },
          402,
        );
      }
      return json({
        ok: true,
        ticketId: result.ticketId,
        amount: result.amount,
        stake: result.stake,
        mode,
        gold: result.gold,
        gems: result.gems,
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/match/ticket/refund") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      let body: { ticketId?: string };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const ticketId = (body.ticketId || "").trim();
      if (!ticketId || ticketId.length > 80) return json({ error: "BadTicket" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const result = await refundOpenTicket(env.DB, {
        pubkey: auth.pubkey,
        ticketId,
        now,
      });
      if (result.status === "not_found") return json({ error: "NotFound" }, 404);
      if (result.status === "not_open") {
        return json({ ok: true, alreadySettled: true, gold: result.gold, gems: result.gems });
      }
      return json({
        ok: true,
        refunded: true,
        amount: result.amount,
        gold: result.gold,
        gems: result.gems,
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/team/session") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      const token = await mintTeamSessionToken(env, auth.pubkey);
      if (!token) return json({ error: "TeamSessionUnavailable" }, 503);
      return json({
        ok: true,
        sessionToken: token,
        expiresInSec: 3600,
        pubkey: auth.pubkey,
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/presence/heartbeat") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const rl = await hitRateLimit(env.DB, `presence:${auth.pubkey}`, 40, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      await heartbeatPresence(env.DB, auth.pubkey, now);
      return json({ ok: true, onlineWindowSec: 90 });
    }

    if (request.method === "POST" && url.pathname === "/v1/presence/query") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const rl = await hitRateLimit(env.DB, `presenceq:${auth.pubkey}`, 30, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      let body: { pubkeys?: string[] };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const online = await queryPresence(env.DB, body.pubkeys || [], now);
      return json({ ok: true, online, onlineWindowSec: 90 });
    }

    // Geo ads consent — stores country from CF edge only (never raw IP).
    if (request.method === "POST" && url.pathname === "/v1/geo/consent") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const rl = await hitRateLimit(env.DB, `geo:${auth.pubkey}`, 10, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      let body: { consent?: boolean };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      if (body.consent !== true) {
        return json({ error: "ConsentRequired", reason: "consent_must_be_true" }, 400);
      }
      const country = countryFromRequest(request);
      await saveGeoConsent(env.DB, auth.pubkey, country, now);
      return json({ ok: true, consent: true, country, consentAt: now });
    }

    if (request.method === "GET" && url.pathname === "/v1/geo/me") {
      const auth = await requireUser(request, env, null);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const rl = await hitRateLimit(env.DB, `geome:${auth.pubkey}`, 30, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      const row = await getGeoConsent(env.DB, auth.pubkey);
      const consent = row?.ad_consent_at != null;
      return json({
        ok: true,
        consent,
        country: consent ? row?.ad_country ?? null : null,
        consentAt: row?.ad_consent_at ?? null,
      });
    }

    // User location for Around Me — country from CF edge; optional GPS fields from client.
    // Never stores raw IP; never writes location into Nostr events.
    if (request.method === "POST" && url.pathname === "/v1/location/update") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const rl = await hitRateLimit(env.DB, `locupd:${auth.pubkey}`, 20, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      let body: {
        consent?: boolean;
        lat?: number | null;
        lon?: number | null;
        geohash?: string | null;
        region?: string | null;
      };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      if (body.consent !== true) {
        return json({ error: "ConsentRequired", reason: "consent_must_be_true" }, 400);
      }
      const country = countryFromRequest(request);
      const row = await saveUserLocation(
        env.DB,
        auth.pubkey,
        country,
        {
          consent: true,
          lat: body.lat,
          lon: body.lon,
          geohash: body.geohash,
          region: body.region,
        },
        now,
      );
      return json({
        ok: true,
        consent: true,
        country: row.loc_country,
        region: row.loc_region,
        geohash: row.loc_geohash,
        updatedAt: row.loc_updated_at,
      });
    }

    if (request.method === "GET" && url.pathname === "/v1/location/me") {
      const auth = await requireUser(request, env, null);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const rl = await hitRateLimit(env.DB, `locme:${auth.pubkey}`, 30, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      const row = await getUserLocation(env.DB, auth.pubkey);
      const consent = row?.loc_consent_at != null;
      return json({
        ok: true,
        consent,
        country: consent ? row?.loc_country ?? null : null,
        region: consent ? row?.loc_region ?? null : null,
        geohash: consent ? row?.loc_geohash ?? null : null,
        consentAt: row?.loc_consent_at ?? null,
        updatedAt: row?.loc_updated_at ?? null,
      });
    }

    if (request.method === "GET" && url.pathname === "/v1/location/nearby") {
      const auth = await requireUser(request, env, null);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      const rl = await hitRateLimit(env.DB, `locnear:${auth.pubkey}`, 30, 60, now);
      if (!rl.allowed) return json({ error: "RateLimited" }, 429);
      const me = await getUserLocation(env.DB, auth.pubkey);
      if (!me?.loc_consent_at || !me.loc_country) {
        return json({ ok: true, country: null, scope: "none", authors: [] });
      }
      const scopeParam = (url.searchParams.get("scope") || "region").toLowerCase();
      const scope = scopeParam === "country" ? "country" : "region";
      const nearby = await listNearbyAuthors(env.DB, me, scope);
      return json({ ok: true, ...nearby });
    }

    if (request.method === "POST" && url.pathname === "/v1/internal/match/bind") {
      if (
        !env.GAME_WORKER_SECRET ||
        !secretEqual(request.headers.get("x-game-secret") || "", env.GAME_WORKER_SECRET)
      ) {
        return json({ error: "Forbidden" }, 403);
      }
      let body: {
        matchId?: string;
        mode?: string;
        tickets?: Array<{ ticketId: string; pubkey: string }>;
      };
      try {
        body = (await request.json()) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const mode = parseMatchMode(body.mode);
      const matchId = (body.matchId || "").trim();
      if (!mode || !matchId || !Array.isArray(body.tickets)) {
        return json({ error: "BadRequest" }, 400);
      }
      const now = Math.floor(Date.now() / 1000);
      const result = await bindMatchTickets(env.DB, {
        matchId,
        mode,
        tickets: body.tickets,
        now,
      });
      if (!result.ok) return json({ error: "BindFailed", reason: result.reason }, 400);
      return json({ ok: true });
    }

    if (request.method === "POST" && url.pathname === "/v1/internal/match/payout") {
      if (
        !env.GAME_WORKER_SECRET ||
        !secretEqual(request.headers.get("x-game-secret") || "", env.GAME_WORKER_SECRET)
      ) {
        return json({ error: "Forbidden" }, 403);
      }
      let body: {
        matchId?: string;
        mode?: string;
        winnerPubkey?: string | null;
        winnerIsBot?: boolean;
      };
      try {
        body = (await request.json()) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const mode = parseMatchMode(body.mode);
      const matchId = (body.matchId || "").trim();
      if (!mode || !matchId) return json({ error: "BadRequest" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const result = await payoutMatchWinner(env.DB, {
        matchId,
        mode,
        winnerPubkey: body.winnerPubkey ?? null,
        winnerIsBot: !!body.winnerIsBot,
        now,
      });
      return json({ ok: true, ...result });
    }

    if (request.method === "POST" && url.pathname === "/v1/admin/credit") {
      const secret = request.headers.get("x-admin-secret") || "";
      if (!env.ADMIN_SECRET || !secretEqual(secret, env.ADMIN_SECRET)) {
        return json({ error: "Forbidden" }, 403);
      }
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      let body: {
        pubkey?: string;
        currency?: "gold" | "gems" | "stars";
        amount?: number;
        idempotencyKey?: string;
      };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const pubkey = (body.pubkey || "").toLowerCase();
      if (!/^[0-9a-f]{64}$/.test(pubkey)) return json({ error: "BadPubkey" }, 400);
      const currency =
        body.currency === "gems" ? "gems" : body.currency === "gold" ? "gold" : body.currency === "stars" ? "stars" : null;
      if (!currency) return json({ error: "BadCurrency" }, 400);
      const amount = Number(body.amount);
      if (!Number.isInteger(amount) || amount <= 0 || amount > 1_000_000) {
        return json({ error: "BadAmount" }, 400);
      }
      const idem =
        (request.headers.get("x-idempotency-key") || body.idempotencyKey || "").trim() ||
        crypto.randomUUID();
      const now = Math.floor(Date.now() / 1000);
      try {
        const bal = await credit(env.DB, {
          pubkey,
          currency,
          amount,
          kind: "admin",
          refType: "admin_credit",
          refId: idem,
          ledgerId: `admin:${idem}`,
          now,
        });
        return json({ ok: true, ...bal, idempotencyKey: idem });
      } catch (e) {
        const msg = e instanceof Error ? e.message : "error";
        if (msg.includes("UNIQUE") || msg.includes("constraint")) {
          return json({ error: "DuplicateIdempotency" }, 409);
        }
        return json({ error: "CreditFailed", detail: msg }, 500);
      }
    }

    // ——— Phase 4: top-up packages / orders / webhook ———
    if (request.method === "GET" && url.pathname === "/v1/topup/packages") {
      return json({ ok: true, ...packagesCatalog() });
    }

    if (request.method === "GET" && url.pathname === "/v1/progress") {
      const pubkeyParam = (url.searchParams.get("pubkey") || "").toLowerCase();
      if (pubkeyParam) {
        if (!/^[0-9a-f]{64}$/.test(pubkeyParam)) return json({ error: "BadPubkey" }, 400);
        const progress = await getProgress(env.DB, pubkeyParam);
        return json({ ok: true, ...progress });
      }
      const auth = await requireUser(request, env, null);
      if (auth instanceof Response) return auth;
      const now = Math.floor(Date.now() / 1000);
      await ensureAccount(env.DB, auth.pubkey, now);
      const progress = await getProgress(env.DB, auth.pubkey);
      return json({ ok: true, ...progress, rewards: XP_REWARDS });
    }

    if (request.method === "POST" && url.pathname === "/v1/progress/award") {
      const secret =
        request.headers.get("x-game-worker-secret") ||
        request.headers.get("x-game-secret") ||
        "";
      if (!env.GAME_WORKER_SECRET || !secretEqual(secret, env.GAME_WORKER_SECRET)) {
        return json({ error: "Forbidden" }, 403);
      }
      const raw = await request.arrayBuffer();
      let body: { pubkey?: string; action?: string; refId?: string; xp?: number };
      try {
        body = JSON.parse(new TextDecoder().decode(new Uint8Array(raw))) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const pubkey = (body.pubkey || "").toLowerCase();
      const action = (body.action || "").trim();
      const refId = (body.refId || "").trim();
      if (!/^[0-9a-f]{64}$/.test(pubkey)) return json({ error: "BadPubkey" }, 400);
      if (!action || action.length > 64) return json({ error: "BadAction" }, 400);
      if (!refId || refId.length > 128) return json({ error: "BadRef" }, 400);
      const now = Math.floor(Date.now() / 1000);
      await ensureAccount(env.DB, pubkey, now);
      const xpOverride =
        body.xp !== undefined && Number.isInteger(body.xp) && body.xp! > 0 && body.xp! <= 500
          ? body.xp
          : undefined;
      const result = await awardXp(env.DB, { pubkey, action, refId, now, xpOverride });
      if (!result.ok) {
        return json({ error: "AwardFailed", reason: result.reason }, result.reason === "duplicate" ? 409 : 400);
      }
      return json({ ok: true, awarded: result.awarded, ...result.progress });
    }

    if (request.method === "POST" && url.pathname === "/v1/topup/create") {
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const auth = await requireUser(request, env, bodyBytes);
      if (auth instanceof Response) return auth;
      let body: { packageId?: string };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const packageId = (body.packageId || "").trim();
      if (!packageId || packageId.length > 40) return json({ error: "BadPackage" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const result = await createTopupOrder(env.DB, {
        pubkey: auth.pubkey,
        packageId,
        now,
      });
      if (result.status === "unknown_package") return json({ error: "UnknownPackage" }, 404);
      return json({
        ok: true,
        orderId: result.orderId,
        packageId: result.packageId,
        gold: result.gold,
        gems: result.gems,
        priceCents: result.priceCents,
        currencyCode: result.currencyCode,
        checkoutEnabled: result.checkoutEnabled,
        // Real Stripe/Zain URL plugged here later — never trust client amounts.
        checkoutUrl: null,
        message: "order_pending_provider",
      });
    }

    /**
     * Payment provider callback. Body must be HMAC-SHA256 hex in
     * X-Payment-Signature using PAYMENT_WEBHOOK_SECRET.
     * Credits ONLY the package amounts stored on the pending order.
     */
    if (request.method === "POST" && url.pathname === "/v1/topup/webhook") {
      if (!env.PAYMENT_WEBHOOK_SECRET) {
        return json({ error: "Misconfigured" }, 503);
      }
      const raw = await request.arrayBuffer();
      const bodyBytes = new Uint8Array(raw);
      const sig = (request.headers.get("x-payment-signature") || "").trim();
      const expected = await hmacSha256Hex(env.PAYMENT_WEBHOOK_SECRET, bodyBytes);
      if (!sig || !timingSafeEqualHex(sig, expected)) {
        return json({ error: "BadSignature" }, 401);
      }
      let body: {
        orderId?: string;
        providerEventId?: string;
        provider?: string;
        status?: string;
      };
      try {
        body = JSON.parse(new TextDecoder().decode(bodyBytes)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      if ((body.status || "paid") !== "paid") {
        return json({ ok: true, ignored: true, status: body.status });
      }
      const orderId = (body.orderId || "").trim();
      const providerEventId = (body.providerEventId || "").trim();
      if (!orderId || !providerEventId) return json({ error: "BadRequest" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const result = await fulfillTopupOrder(env.DB, {
        orderId,
        providerEventId,
        provider: (body.provider || "webhook").slice(0, 40),
        now,
      });
      if (result.status === "not_found") return json({ error: "NotFound" }, 404);
      if (result.status === "not_pending") return json({ error: "NotPending" }, 409);
      if (result.status === "pubkey_mismatch") return json({ error: "Forbidden" }, 403);
      return json({ ok: true, ...result });
    }

    /** Ops: mark a pending order paid (testing / manual settlement). */
    if (request.method === "POST" && url.pathname === "/v1/admin/topup/fulfill") {
      const secret = request.headers.get("x-admin-secret") || "";
      if (!env.ADMIN_SECRET || !secretEqual(secret, env.ADMIN_SECRET)) {
        return json({ error: "Forbidden" }, 403);
      }
      const raw = await request.arrayBuffer();
      let body: { orderId?: string; providerEventId?: string };
      try {
        body = JSON.parse(new TextDecoder().decode(raw)) as typeof body;
      } catch {
        return json({ error: "BadJson" }, 400);
      }
      const orderId = (body.orderId || "").trim();
      if (!orderId) return json({ error: "BadOrder" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const providerEventId =
        (body.providerEventId || "").trim() || `admin:${orderId}:${now}`;
      const result = await fulfillTopupOrder(env.DB, {
        orderId,
        providerEventId,
        provider: "admin",
        now,
      });
      if (result.status === "not_found") return json({ error: "NotFound" }, 404);
      if (result.status === "not_pending") return json({ error: "NotPending" }, 409);
      return json({ ok: true, ...result });
    }

    return json({ error: "NotFound" }, 404);
  },
};
