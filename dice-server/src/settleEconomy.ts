import type { Env } from "./index";
import { payoutMatchWinner, type MatchMode } from "./economyBridge";
import { awardProgressXp } from "./progressBridge";

async function settleMatchProgress(
  env: Env,
  opts: {
    matchId: string;
    winnerColor: string | null | undefined;
    seats: Array<{ color: string; playerId: string; isBot?: boolean }>;
  },
): Promise<void> {
  const humanSeats = opts.seats.filter((s) => !s.isBot);
  if (humanSeats.length === 0) return;

  const winnerSeat = opts.seats.find((s) => s.color === opts.winnerColor);
  const winnerPubkey =
    winnerSeat && !winnerSeat.isBot ? winnerSeat.playerId.toLowerCase() : null;

  const tasks: Promise<void>[] = [];
  for (const seat of humanSeats) {
    const pubkey = seat.playerId.toLowerCase();
    tasks.push(
      awardProgressXp(env, {
        pubkey,
        action: "ludo_match_played",
        refId: `${opts.matchId}:played`,
      }),
    );
    if (winnerPubkey) {
      tasks.push(
        awardProgressXp(env, {
          pubkey,
          action: pubkey === winnerPubkey ? "ludo_match_win" : "ludo_match_lose",
          refId: `${opts.matchId}:${pubkey === winnerPubkey ? "win" : "lose"}`,
        }),
      );
    }
  }
  await Promise.all(tasks);
}

export async function settleMatchEconomy(
  env: Env,
  opts: {
    matchId: string;
    mode: MatchMode;
    winnerColor: string | null | undefined;
    seats: Array<{ color: string; playerId: string; isBot?: boolean }>;
  },
): Promise<void> {
  const seat = opts.seats.find((s) => s.color === opts.winnerColor);
  await Promise.all([
    payoutMatchWinner(env, {
      matchId: opts.matchId,
      mode: opts.mode,
      winnerPubkey: seat && !seat.isBot ? seat.playerId : null,
      winnerIsBot: !seat || !!seat.isBot,
    }),
    settleMatchProgress(env, opts),
  ]);
}
