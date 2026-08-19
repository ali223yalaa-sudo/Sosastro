import { describe, expect, it } from "vitest";
import { dieFaceWeights, fairDie } from "./fairDie";

describe("fairDie", () => {
  it("maps the first weight slots to faces in table order", () => {
    // weights: 1×3, 2×3, 3×3, 4×5, 5×3, 6×7  (total 24)
    expect(fairDie(() => 0)).toBe(1);
    expect(fairDie(() => 3)).toBe(2);
    expect(fairDie(() => 6)).toBe(3);
    expect(fairDie(() => 9)).toBe(4);
    expect(fairDie(() => 14)).toBe(5);
    expect(fairDie(() => 17)).toBe(6);
    expect(fairDie(() => 23)).toBe(6);
  });

  it("rejects the biased tail before choosing a face", () => {
    const values = [0xffff_ffff, 17];
    expect(fairDie(() => values.shift()!)).toBe(6);
    expect(values).toEqual([]);
  });

  it("gives 6 the highest weight and 4 the next", () => {
    const byFace = Object.fromEntries(dieFaceWeights().map((e) => [e.face, e.weight]));
    expect(byFace[6]).toBeGreaterThan(byFace[4]!);
    expect(byFace[4]).toBeGreaterThan(byFace[1]!);
    expect(byFace[4]).toBeGreaterThan(byFace[2]!);
    expect(byFace[4]).toBeGreaterThan(byFace[3]!);
    expect(byFace[4]).toBeGreaterThan(byFace[5]!);
  });
});
