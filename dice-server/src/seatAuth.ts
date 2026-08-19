/**
 * Short-lived seat proofs for GameRoom WebSockets (nests-style: mint after
 * bind, verify before accepting the seat). Prevents opponent seat hijack
 * via known playerId + matchId.
 *
 * Token format: base64url(payloadJson).base64url(hmacSha256)
 * payload: { mid, pid, exp }  — mid=matchId, pid=pubkey hex, exp=unix sec
 */
import type { Env } from "./index";

const DEFAULT_TTL_SEC = 2 * 60 * 60; // full match + reconnect window

function b64urlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]!);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function b64urlDecode(s: string): Uint8Array {
  const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - (s.length % 4));
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + pad;
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function utf8(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i]! ^ b[i]!;
  return diff === 0;
}

async function hmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    utf8(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

async function signPayload(secret: string, payloadB64: string): Promise<string> {
  const key = await hmacKey(secret);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, utf8(payloadB64)));
  return b64urlEncode(sig);
}

export function seatSecret(env: Env): string | null {
  const s = env.SEAT_TOKEN_SECRET || env.GAME_WORKER_SECRET;
  return s && s.length >= 16 ? s : null;
}

export async function mintSeatToken(
  env: Env,
  opts: { matchId: string; playerId: string; ttlSec?: number; nowSec?: number },
): Promise<string | null> {
  const secret = seatSecret(env);
  if (!secret) return null;
  const now = opts.nowSec ?? Math.floor(Date.now() / 1000);
  const payload = {
    mid: opts.matchId,
    pid: opts.playerId.toLowerCase(),
    exp: now + (opts.ttlSec ?? DEFAULT_TTL_SEC),
  };
  const payloadB64 = b64urlEncode(utf8(JSON.stringify(payload)));
  const sig = await signPayload(secret, payloadB64);
  return `${payloadB64}.${sig}`;
}

export type SeatVerifyOk = { ok: true };
export type SeatVerifyErr = { ok: false; reason: string };

export async function verifySeatToken(
  env: Env,
  opts: { token: string; matchId: string; playerId: string; nowSec?: number },
): Promise<SeatVerifyOk | SeatVerifyErr> {
  const secret = seatSecret(env);
  if (!secret) return { ok: false, reason: "misconfigured" };
  const token = (opts.token || "").trim();
  const parts = token.split(".");
  if (parts.length !== 2) return { ok: false, reason: "bad_token" };
  const [payloadB64, sigB64] = parts as [string, string];
  const expectedSig = await signPayload(secret, payloadB64);
  if (!timingSafeEqual(b64urlDecode(sigB64), b64urlDecode(expectedSig))) {
    return { ok: false, reason: "bad_sig" };
  }
  let payload: { mid?: string; pid?: string; exp?: number };
  try {
    payload = JSON.parse(new TextDecoder().decode(b64urlDecode(payloadB64))) as typeof payload;
  } catch {
    return { ok: false, reason: "bad_payload" };
  }
  const now = opts.nowSec ?? Math.floor(Date.now() / 1000);
  if (!payload.mid || !payload.pid || typeof payload.exp !== "number") {
    return { ok: false, reason: "bad_claims" };
  }
  if (payload.exp < now) return { ok: false, reason: "expired" };
  if (payload.mid !== opts.matchId) return { ok: false, reason: "match_mismatch" };
  if (payload.pid !== opts.playerId.toLowerCase()) return { ok: false, reason: "player_mismatch" };
  return { ok: true };
}

/** Shared WS gate for all GameRoom*. */
export async function assertRoomSeat(
  env: Env,
  opts: { matchId: string; playerId: string; seatToken: string },
): Promise<Response | null> {
  if (!opts.playerId || !/^[0-9a-f]{64}$/i.test(opts.playerId)) {
    return new Response("Forbidden", { status: 403 });
  }
  const v = await verifySeatToken(env, {
    token: opts.seatToken,
    matchId: opts.matchId,
    playerId: opts.playerId,
  });
  if (!v.ok) {
    return new Response(`Forbidden:${v.reason}`, { status: 403 });
  }
  return null;
}

export function buildRoomWebsocketUrl(opts: {
  host: string;
  path: string; // e.g. /room/{id} or /room4/{id}
  playerId: string;
  seatToken: string;
}): string {
  const u = new URL(`wss://${opts.host}${opts.path}`);
  u.searchParams.set("playerId", opts.playerId);
  u.searchParams.set("seatToken", opts.seatToken);
  return u.toString();
}
