import { describe, expect, it } from "vitest";
import { createPawns, createPlayers } from "./engine";
import {
  allLegalPawnIds,
  consumePlayedFace,
  facesForPiece,
  pickAutoPair,
  pruneUnusableFaces,
} from "./moveQueue";

const players = createPlayers(
  { playerId: "a", name: "A" },
  { playerId: "b", name: "B" },
);

describe("choice move queue", () => {
  it("yard piece can only use 6 from [6,4]", () => {
    const pawns = createPawns(players);
    const id = "RED_0";
    expect(facesForPiece(pawns, "RED", [6, 4], id)).toEqual([6]);
    expect(allLegalPawnIds(pawns, "RED", [6, 4])).toContain(id);
  });

  it("after consuming 6, only 4 remains for an on-track piece", () => {
    const pawns = createPawns(players).map((p) =>
      p.id === "RED_0" ? { ...p, step: 0 } : p,
    );
    const q = consumePlayedFace([6, 4], 6);
    expect(q).toEqual([4]);
    expect(facesForPiece(pawns, "RED", q, "RED_0")).toEqual([4]);
  });

  it("on-track piece can choose 6 or 4 when both are legal", () => {
    const pawns = createPawns(players).map((p) =>
      p.id === "RED_0" ? { ...p, step: 0 } : p,
    );
    expect(facesForPiece(pawns, "RED", [6, 4], "RED_0").sort()).toEqual([4, 6]);
  });

  it("auto prefers a six when available", () => {
    const pawns = createPawns(players);
    const pick = pickAutoPair(pawns, "RED", [4, 6]);
    expect(pick?.face).toBe(6);
  });

  it("prunes faces nobody can use", () => {
    const pawns = createPawns(players);
    // All in yard — 4 unusable, 6 usable.
    const { queue, skipped } = pruneUnusableFaces(pawns, "RED", [4, 6]);
    // 4 is kept: a remaining 6 can exit a yard piece, then 4 becomes playable.
    expect(skipped).toEqual([]);
    expect(queue).toEqual([4, 6]);
  });

  it("keeps non-six after first yard exit chain [6,4]", () => {
    const pawns = createPawns(players);
    const { queue } = pruneUnusableFaces(pawns, "RED", [6, 4]);
    expect(queue).toEqual([6, 4]);
    expect(facesForPiece(pawns, "RED", queue, "RED_0")).toEqual([6]);

    // After playing the 6 to exit, the 4 must still be available.
    const afterExit = pawns.map((p) => (p.id === "RED_0" ? { ...p, step: 0 } : p));
    const remaining = consumePlayedFace(queue, 6);
    expect(remaining).toEqual([4]);
    const pruned = pruneUnusableFaces(afterExit, "RED", remaining);
    expect(pruned.queue).toEqual([4]);
    expect(facesForPiece(afterExit, "RED", pruned.queue, "RED_0")).toEqual([4]);
  });

  it("still drains when all in yard and no six", () => {
    const pawns = createPawns(players);
    const { queue, skipped } = pruneUnusableFaces(pawns, "RED", [4, 5]);
    expect(queue).toEqual([]);
    expect(skipped).toEqual([4, 5]);
  });
});