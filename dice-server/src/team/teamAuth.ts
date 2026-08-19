/**
 * Team session + lobby tokens (HMAC) — separate from game seat tokens.
 * Minted by economy Worker (NIP-98); verified on dice team routes.
 */
import type { Env } from "../index";
import { seatSecret } from "../seatAuth";

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
  return crypto.subtle.importKey("raw", utf8(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
}

async function signPayload(secret: string, payloadB64: string): Promise<string> {
  const key = await hmacKey(secret);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, utf8(payloadB64)));
  return b64urlEncode(sig);
}

export type TeamTokenClaims = {
  typ: "team_session" | "lobby_host" | "lobby_member";
  pid: string;
  lid?: string;
  exp: number;
};

export async function mintTeamToken(
  env: Env,
  claims: Omit<TeamTokenClaims, "exp"> & { ttlSec?: number; nowSec?: number },
): Promise<string | null> {
  const secret = seatSecret(env);
  if (!secret) return null;
  const now = claims.nowSec ?? Math.floor(Date.now() / 1000);
  const payload: TeamTokenClaims = {
    typ: claims.typ,
    pid: claims.pid.toLowerCase(),
    lid: claims.lid,
    exp: now + (claims.ttlSec ?? 3600),
  };
  const payloadB64 = b64urlEncode(utf8(JSON.stringify(payload)));
  const sig = await signPayload(secret, payloadB64);
  return `${payloadB64}.${sig}`;
}

export async function verifyTeamToken(
  env: Env,
  opts: { token: string; expectedType: TeamTokenClaims["typ"]; playerId: string; lobbyId?: string },
): Promise<{ ok: true; claims: TeamTokenClaims } | { ok: false; reason: string }> {
  const secret = seatSecret(env);
  if (!secret) return { ok: false, reason: "misconfigured" };
  const parts = opts.token.trim().split(".");
  if (parts.length !== 2) return { ok: false, reason: "bad_token" };
  const [payloadB64, sigB64] = parts as [string, string];
  const expectedSig = await signPayload(secret, payloadB64);
  if (!timingSafeEqual(b64urlDecode(sigB64), b64urlDecode(expectedSig))) {
    return { ok: false, reason: "bad_sig" };
  }
  let claims: TeamTokenClaims;
  try {
    claims = JSON.parse(new TextDecoder().decode(b64urlDecode(payloadB64))) as TeamTokenClaims;
  } catch {
    return { ok: false, reason: "bad_payload" };
  }
  const now = Math.floor(Date.now() / 1000);
  if (claims.exp < now) return { ok: false, reason: "expired" };
  if (claims.typ !== opts.expectedType) return { ok: false, reason: "type_mismatch" };
  if (claims.pid !== opts.playerId.toLowerCase()) return { ok: false, reason: "player_mismatch" };
  if (opts.lobbyId && claims.lid !== opts.lobbyId) return { ok: false, reason: "lobby_mismatch" };
  return { ok: true, claims };
}
