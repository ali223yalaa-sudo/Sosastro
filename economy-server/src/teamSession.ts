/** Mint team_session HMAC token (same format as dice-server teamAuth). */
import type { Env } from "./ledger";

function b64urlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]!);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function utf8(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

async function signPayload(secret: string, payloadB64: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", utf8(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, utf8(payloadB64)));
  return b64urlEncode(sig);
}

export async function mintTeamSessionToken(env: Env, pubkey: string): Promise<string | null> {
  const secret = env.GAME_WORKER_SECRET;
  if (!secret || secret.length < 16) return null;
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    typ: "team_session",
    pid: pubkey.toLowerCase(),
    exp: now + 3600,
  };
  const payloadB64 = b64urlEncode(utf8(JSON.stringify(payload)));
  const sig = await signPayload(secret, payloadB64);
  return `${payloadB64}.${sig}`;
}
