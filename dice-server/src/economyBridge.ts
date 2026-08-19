/**
 * Economy bridge — dice Worker → economy Worker (shared secret).
 * Never expose GAME_WORKER_SECRET to the APK.
 */
import type { Env } from "./index";

export type MatchMode =
  | "classic_1v1"
  | "classic_4p"
  | "arrow_1v1"
  | "arrow_4p"
  | "team_classic_2v2"
  | "team_arrow_2v2";

export async function bindMatchTickets(
  env: Env,
  opts: {
    matchId: string;
    mode: MatchMode;
    tickets: Array<{ ticketId: string; pubkey: string }>;
  },
): Promise<{ ok: true } | { ok: false; reason: string }> {
  const base = env.ECONOMY_BASE_URL?.replace(/\/$/, "");
  const secret = env.GAME_WORKER_SECRET;
  if (!base || !secret) {
    // Misconfigured — fail closed so free matches cannot skip the vault.
    return { ok: false, reason: "economy_not_configured" };
  }
  try {
    const resp = await fetch(`${base}/v1/internal/match/bind`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-game-secret": secret,
      },
      body: JSON.stringify(opts),
    });
    if (!resp.ok) {
      const text = await resp.text();
      return { ok: false, reason: `bind_http_${resp.status}:${text.slice(0, 120)}` };
    }
    return { ok: true };
  } catch (e) {
    return { ok: false, reason: e instanceof Error ? e.message : "bind_error" };
  }
}

export async function payoutMatchWinner(
  env: Env,
  opts: {
    matchId: string;
    mode: MatchMode;
    winnerPubkey: string | null;
    winnerIsBot: boolean;
  },
): Promise<void> {
  const base = env.ECONOMY_BASE_URL?.replace(/\/$/, "");
  const secret = env.GAME_WORKER_SECRET;
  if (!base || !secret) return;
  try {
    await fetch(`${base}/v1/internal/match/payout`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-game-secret": secret,
      },
      body: JSON.stringify(opts),
    });
  } catch {
    // Best-effort; match_payouts uniqueness makes retries safe later.
  }
}
