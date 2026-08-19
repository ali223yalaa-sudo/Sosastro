/**
 * NIP-98 HTTP auth verification (kind 27235) for Cloudflare Workers.
 * Same security model loyalty apps use: prove key ownership per request,
 * short TTL, method+URL bind, optional body hash, anti-replay.
 */
import { schnorr } from "@noble/curves/secp256k1";
import { sha256 } from "@noble/hashes/sha2";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils";

export type NostrEvent = {
  id: string;
  pubkey: string;
  created_at: number;
  kind: number;
  tags: string[][];
  content: string;
  sig: string;
};

const KIND = 27235;
const TOLERANCE_SEC = 60;

function utf8(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

/** NIP-01 event id serialization. */
export function computeEventId(ev: Omit<NostrEvent, "id" | "sig">): string {
  const payload = JSON.stringify([0, ev.pubkey, ev.created_at, ev.kind, ev.tags, ev.content]);
  return bytesToHex(sha256(utf8(payload)));
}

export function verifySchnorr(ev: NostrEvent): boolean {
  try {
    if (!/^[0-9a-f]{64}$/i.test(ev.pubkey)) return false;
    if (!/^[0-9a-f]{64}$/i.test(ev.id)) return false;
    if (!/^[0-9a-f]{128}$/i.test(ev.sig)) return false;
    const expected = computeEventId({
      pubkey: ev.pubkey.toLowerCase(),
      created_at: ev.created_at,
      kind: ev.kind,
      tags: ev.tags,
      content: ev.content,
    });
    if (expected !== ev.id.toLowerCase()) return false;
    return schnorr.verify(hexToBytes(ev.sig), hexToBytes(ev.id), hexToBytes(ev.pubkey));
  } catch {
    return false;
  }
}

function tagValue(tags: string[][], name: string): string | null {
  for (const t of tags) {
    if (t[0] === name && t[1]) return t[1];
  }
  return null;
}

export function sha256Hex(data: Uint8Array): string {
  return bytesToHex(sha256(data));
}

export type Nip98Ok = { ok: true; pubkey: string; eventId: string };
export type Nip98Err = { ok: false; reason: string };
export type Nip98Result = Nip98Ok | Nip98Err;

/**
 * Verify Authorization: Nostr <base64-event>
 */
export function verifyNip98Header(opts: {
  authorization: string | null;
  method: string;
  url: string;
  body: Uint8Array | null;
  nowSec?: number;
}): Nip98Result {
  const header = opts.authorization;
  if (!header || !header.startsWith("Nostr ")) {
    return { ok: false, reason: "missing_auth" };
  }
  const b64 = header.slice("Nostr ".length).trim();
  let json: string;
  try {
    const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    json = new TextDecoder().decode(bin);
  } catch {
    return { ok: false, reason: "bad_base64" };
  }

  let ev: NostrEvent;
  try {
    ev = JSON.parse(json) as NostrEvent;
  } catch {
    return { ok: false, reason: "bad_json" };
  }

  if (ev.kind !== KIND) return { ok: false, reason: "bad_kind" };
  if (!verifySchnorr(ev)) return { ok: false, reason: "bad_signature" };

  const now = opts.nowSec ?? Math.floor(Date.now() / 1000);
  if (Math.abs(now - ev.created_at) > TOLERANCE_SEC) {
    return { ok: false, reason: "expired" };
  }

  const method = tagValue(ev.tags, "method");
  const u = tagValue(ev.tags, "u");
  if (!method || method.toUpperCase() !== opts.method.toUpperCase()) {
    return { ok: false, reason: "method_mismatch" };
  }
  if (!u || normalizeUrl(u) !== normalizeUrl(opts.url)) {
    return { ok: false, reason: "url_mismatch" };
  }

  if (opts.body && opts.body.byteLength > 0) {
    const payload = tagValue(ev.tags, "payload");
    const hash = sha256Hex(opts.body);
    if (!payload || payload.toLowerCase() !== hash) {
      return { ok: false, reason: "payload_mismatch" };
    }
  }

  return { ok: true, pubkey: ev.pubkey.toLowerCase(), eventId: ev.id.toLowerCase() };
}

function normalizeUrl(raw: string): string {
  try {
    const u = new URL(raw);
    // Drop default ports; keep path+query exact.
    u.hash = "";
    return u.toString().replace(/\/$/, "") === u.origin ? u.toString().replace(/\/$/, "") || u.origin : u.toString();
  } catch {
    return raw;
  }
}

/** Stricter compare used by the worker after building expected absolute URL. */
export function urlsEqual(a: string, b: string): boolean {
  return normalizeUrl(a) === normalizeUrl(b);
}
