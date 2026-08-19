/**
 * 4-player Ludo engine — parallel to engine.ts (2p). Do not import from GameRoom (1v1).
 */
export type Color = "RED" | "YELLOW" | "GREEN" | "BLUE";

export type Pawn = {
  id: string;
  color: Color;
  index: number;
  /** -1 yard, 0..50 track, 51..55 home, 56 finished */
  step: number;
};

export type Player = {
  playerId: string;
  name: string;
  color: Color;
  isBot?: boolean;
  pieceStyleId?: string;
  diceStyleId?: string;
};

/**
 * Seat / turn order matching board-house clockwise after pure rotation:
 * from every seat the next player sits at top-left (TL).
 * RED → GREEN → YELLOW → BLUE. Do not alter track path — only seat order.
 */
const COLORS: Color[] = ["RED", "GREEN", "YELLOW", "BLUE"];

const START: Record<Color, number> = {
  RED: 40,
  YELLOW: 14,
  GREEN: 1,
  BLUE: 27,
};

const SAFE = new Set([1, 9, 14, 22, 27, 35, 40, 48]);

export function createPlayersFromList(
  seats: Array<{ playerId: string; name: string; isBot?: boolean; pieceStyleId?: string; diceStyleId?: string }>,
): Player[] {
  if (seats.length !== 4) {
    throw new Error(`4-player match requires exactly 4 seats, got ${seats.length}`);
  }
  return seats.map((s, i) => ({
    playerId: s.playerId,
    name: s.name,
    color: COLORS[i]!,
    isBot: !!s.isBot,
    pieceStyleId: s.pieceStyleId || "disc",
    diceStyleId: s.diceStyleId || "cdr_classic",
  }));
}

export function createPawns(players: Player[]): Pawn[] {
  return players.flatMap((player) =>
    [0, 1, 2, 3].map((index) => ({
      id: `${player.color}_${index}`,
      color: player.color,
      index,
      step: -1,
    })),
  );
}

export function absoluteTrackIndex(color: Color, step: number): number {
  if (step === 0) return START[color];
  if (step < 1 || step > 50) return -1;
  return (START[color] + step) % 52;
}

export function destinationStep(pawn: Pawn, dice: number): number | null {
  if (dice < 1 || dice > 6) return null;
  if (pawn.step >= 56) return null;
  if (pawn.step < 0) return dice === 6 ? 0 : null;
  const dest = pawn.step + dice;
  if (dest > 56) return null;
  return dest;
}

export function pathSteps(pawn: Pawn, dice: number): number[] {
  const dest = destinationStep(pawn, dice);
  if (dest === null) return [];
  if (pawn.step < 0) return [0];
  const out: number[] = [];
  for (let s = pawn.step + 1; s <= dest; s += 1) out.push(s);
  return out;
}

function blockedBySafeOpponent(pawns: Pawn[], moving: Pawn, toStep: number): boolean {
  if (toStep < 0 || toStep > 50) return false;
  const abs = absoluteTrackIndex(moving.color, toStep);
  if (abs < 0 || !SAFE.has(abs)) return false;
  return pawns.some(
    (other) =>
      other.color !== moving.color &&
      other.step >= 0 &&
      other.step <= 50 &&
      absoluteTrackIndex(other.color, other.step) === abs,
  );
}

export function legalPawns(pawns: Pawn[], color: Color, dice: number): Pawn[] {
  return pawns
    .filter((pawn) => {
      if (pawn.color !== color) return false;
      const dest = destinationStep(pawn, dice);
      if (dest === null) return false;
      return !blockedBySafeOpponent(pawns, pawn, dest);
    })
    .sort((a, b) => a.index - b.index);
}

export function applyMove(
  pawns: Pawn[],
  pieceId: string,
  dice: number,
  color: Color,
): {
  fromStep: number;
  toStep: number;
  path: number[];
  capturedPieceIds: string[];
  pawns: Pawn[];
  finished: boolean;
} {
  const pawn = pawns.find((p) => p.id === pieceId);
  if (!pawn || pawn.color !== color) throw new Error("Illegal move");
  const path = pathSteps(pawn, dice);
  if (path.length === 0) throw new Error("Illegal move");
  const toStep = path[path.length - 1]!;
  if (blockedBySafeOpponent(pawns, pawn, toStep)) throw new Error("Illegal move");

  const fromStep = pawn.step;
  const next = pawns.map((p) => (p.id === pieceId ? { ...p, step: toStep } : { ...p }));
  const captured: string[] = [];
  if (toStep >= 0 && toStep <= 50) {
    const abs = absoluteTrackIndex(color, toStep);
    if (abs >= 0 && !SAFE.has(abs)) {
      for (const other of next) {
        if (
          other.color !== color &&
          other.step >= 0 &&
          other.step <= 50 &&
          absoluteTrackIndex(other.color, other.step) === abs
        ) {
          other.step = -1;
          captured.push(other.id);
        }
      }
    }
  }
  return {
    fromStep,
    toStep,
    path,
    capturedPieceIds: captured,
    pawns: next,
    finished: toStep === 56,
  };
}

export function winner(pawns: Pawn[], players: Player[]): Color | null {
  for (const player of players) {
    const mine = pawns.filter((p) => p.color === player.color);
    if (mine.length === 4 && mine.every((p) => p.step >= 56)) return player.color;
  }
  return null;
}
