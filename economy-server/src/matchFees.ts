/** Match stakes (gold) chosen by the player on the lobby. */
export type MatchMode =
  | "classic_1v1"
  | "classic_4p"
  | "arrow_1v1"
  | "arrow_4p"
  | "team_classic_2v2"
  | "team_arrow_2v2";

export const ALLOWED_STAKES = [10, 25, 50, 100] as const;
export type MatchStake = (typeof ALLOWED_STAKES)[number];

export function parseMatchMode(raw: unknown): MatchMode | null {
  if (
    raw === "classic_1v1" ||
    raw === "classic_4p" ||
    raw === "arrow_1v1" ||
    raw === "arrow_4p" ||
    raw === "team_classic_2v2" ||
    raw === "team_arrow_2v2"
  ) {
    return raw;
  }
  return null;
}

export function parseStake(raw: unknown): MatchStake | null {
  const n = Number(raw);
  return (ALLOWED_STAKES as readonly number[]).includes(n) ? (n as MatchStake) : null;
}

export function isFourPlayerMode(mode: MatchMode): boolean {
  return mode === "classic_4p" || mode === "arrow_4p" || mode === "team_classic_2v2" || mode === "team_arrow_2v2";
}

/** Winner payout from a single stake size (house keeps the cut). */
export function payoutForStake(mode: MatchMode, stake: number): number {
  if (isFourPlayerMode(mode)) {
    return Math.floor(stake * 3.5);
  }
  return Math.floor(stake * 1.8);
}

/**
 * Authoritative payout:
 * - 2+ human tickets → competitive formula (1.8× / 3.5×)
 * - 1 human (bot fill) → break-even (= stake) so farming bots cannot mint gold
 */
export function payoutForMatch(opts: {
  mode: MatchMode;
  stake: number;
  humanTicketCount: number;
}): number {
  if (opts.stake <= 0) return 0;
  if (opts.humanTicketCount <= 1) {
    return opts.stake;
  }
  return payoutForStake(opts.mode, opts.stake);
}

/** Public fees browse payload. */
export function feesCatalog() {
  return {
    stakes: [...ALLOWED_STAKES],
    payouts: {
      "1v1": Object.fromEntries(ALLOWED_STAKES.map((s) => [s, payoutForStake("classic_1v1", s)])),
      "4p": Object.fromEntries(ALLOWED_STAKES.map((s) => [s, payoutForStake("classic_4p", s)])),
      "team_2v2": Object.fromEntries(ALLOWED_STAKES.map((s) => [s, payoutForStake("team_classic_2v2", s)])),
    },
  };
}
