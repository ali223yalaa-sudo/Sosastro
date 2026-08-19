/**
 * Progress bridge — dice Worker → economy Worker XP awards (shared secret).
 */
import type { Env } from "./index";

export async function awardProgressXp(
  env: Env,
  opts: { pubkey: string; action: string; refId: string },
): Promise<void> {
  const base = env.ECONOMY_BASE_URL?.replace(/\/$/, "");
  const secret = env.GAME_WORKER_SECRET;
  if (!base || !secret) return;
  const pubkey = opts.pubkey.toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(pubkey)) return;
  try {
    await fetch(`${base}/v1/progress/award`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-game-secret": secret,
        "x-game-worker-secret": secret,
      },
      body: JSON.stringify({
        pubkey,
        action: opts.action,
        refId: opts.refId,
      }),
    });
  } catch {
    // Best-effort; xp_events uniqueness makes retries safe.
  }
}

/** XP for captures and pieces reaching home during a move. */
export async function awardMoveProgressXp(
  env: Env,
  opts: {
    matchId: string;
    turnId: string;
    actorPubkey: string;
    actorIsBot: boolean;
    pieceId: string;
    capturedPieceIds: string[];
    finished: boolean;
  },
): Promise<void> {
  if (opts.actorIsBot) return;
  const baseRef = `${opts.matchId}:${opts.turnId}:${opts.pieceId}`;
  const tasks: Promise<void>[] = [];
  for (const capId of opts.capturedPieceIds) {
    tasks.push(
      awardProgressXp(env, {
        pubkey: opts.actorPubkey,
        action: "ludo_piece_captured",
        refId: `${baseRef}:cap:${capId}`,
      }),
    );
  }
  if (opts.finished) {
    tasks.push(
      awardProgressXp(env, {
        pubkey: opts.actorPubkey,
        action: "ludo_piece_home",
        refId: `${baseRef}:home`,
      }),
    );
  }
  await Promise.all(tasks);
}
