import { legalPawns, type Color, type Pawn } from "./engine";

/** Unique faces still in the queue that this piece can legally use. */
export function facesForPiece(
  pawns: Pawn[],
  color: Color,
  queue: number[],
  pieceId: string,
): number[] {
  const out: number[] = [];
  const seen = new Set<number>();
  for (const face of queue) {
    if (seen.has(face)) continue;
    const legal = legalPawns(pawns, color, face);
    if (legal.some((p) => p.id === pieceId)) {
      seen.add(face);
      out.push(face);
    }
  }
  return out;
}

/** Every pawn that can use at least one remaining face. */
export function allLegalPawnIds(pawns: Pawn[], color: Color, queue: number[]): string[] {
  const ids = new Set<string>();
  for (const face of queue) {
    for (const p of legalPawns(pawns, color, face)) {
      ids.add(p.id);
    }
  }
  return [...ids].sort();
}

/** Drop faces that can never be used — keep faces that a remaining 6 may unlock. */
export function pruneUnusableFaces(
  pawns: Pawn[],
  color: Color,
  queue: number[],
): { queue: number[]; skipped: number[] } {
  const skipped: number[] = [];
  const next = [...queue];
  const hasSix = next.includes(6);
  const hasYardPiece = pawns.some((p) => p.color === color && p.step < 0);

  // Nothing playable now and no six left to exit a yard piece → drain.
  if (next.length > 0 && allLegalPawnIds(pawns, color, next).length === 0) {
    if (!(hasSix && hasYardPiece)) {
      skipped.push(...next);
      return { queue: [], skipped };
    }
  }

  const kept: number[] = [];
  for (const face of next) {
    if (legalPawns(pawns, color, face).length > 0) {
      kept.push(face);
      continue;
    }
    // First exit case: [6, 4] while all in yard — keep 4 until after the 6 exits.
    if (hasSix && hasYardPiece && face !== 6) {
      kept.push(face);
      continue;
    }
    skipped.push(face);
  }
  return { queue: kept, skipped };
}

/** Pick any legal (piece, face) for autoplay — prefer sixes for yard exits. */
export function pickAutoPair(
  pawns: Pawn[],
  color: Color,
  queue: number[],
): { pieceId: string; face: number } | null {
  const preferred = [...queue].sort((a, b) => {
    if (a === 6 && b !== 6) return -1;
    if (b === 6 && a !== 6) return 1;
    return 0;
  });
  for (const face of preferred) {
    const legal = legalPawns(pawns, color, face);
    if (legal.length > 0) {
      const piece = legal[Math.floor(Math.random() * legal.length)]!;
      return { pieceId: piece.id, face };
    }
  }
  return null;
}

/** After playing `face`, remove one occurrence from the queue. */
export function consumePlayedFace(queue: number[], face: number): number[] {
  if (queue.length === 0) return [];
  const idx = queue.indexOf(face);
  if (idx >= 0) return [...queue.slice(0, idx), ...queue.slice(idx + 1)];
  return queue.slice(1);
}

/**
 * @deprecated FIFO helper — kept for tests of prune behavior compatibility.
 */
export function nextPlayableFace(
  pawns: Pawn[],
  color: Color,
  queue: number[],
): { face: number; legalIds: string[]; remaining: number[]; skipped: number[] } | null {
  const pruned = pruneUnusableFaces(pawns, color, queue);
  if (pruned.queue.length === 0) return null;
  const face = pruned.queue[0]!;
  return {
    face,
    legalIds: legalPawns(pawns, color, face).map((p) => p.id),
    remaining: pruned.queue,
    skipped: pruned.skipped,
  };
}
