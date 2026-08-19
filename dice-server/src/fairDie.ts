const UINT32_RANGE = 0x1_0000_0000;

/**
 * Fun-weighted Ludo faces (still crypto-backed, no modulo bias).
 *
 * Face weights:
 *   6 → 7  (~29%)  — leave yard / bonus turn most often
 *   4 → 5  (~21%)  — strong mid roll
 *   1,2,3,5 → 3 each (~12.5% each)
 *
 * Total weight = 24.
 */
const FACE_WEIGHTS: ReadonlyArray<{ face: number; weight: number }> = [
  { face: 1, weight: 3 },
  { face: 2, weight: 3 },
  { face: 3, weight: 3 },
  { face: 4, weight: 5 },
  { face: 5, weight: 3 },
  { face: 6, weight: 7 },
];

const WEIGHT_TOTAL = FACE_WEIGHTS.reduce((sum, entry) => sum + entry.weight, 0);
const FAIR_LIMIT = UINT32_RANGE - (UINT32_RANGE % WEIGHT_TOTAL);

/** Maps cryptographic uint32 values to a weighted 1..6 face. */
export function fairDie(nextUint32: () => number): number {
  let random: number;
  do {
    random = nextUint32() >>> 0;
  } while (random >= FAIR_LIMIT);
  let slot = random % WEIGHT_TOTAL;
  for (const entry of FACE_WEIGHTS) {
    if (slot < entry.weight) return entry.face;
    slot -= entry.weight;
  }
  return 6;
}

export function secureUint32(): number {
  const values = new Uint32Array(1);
  crypto.getRandomValues(values);
  return values[0];
}

/** Exported for tests — face weight table. */
export function dieFaceWeights(): ReadonlyArray<{ face: number; weight: number }> {
  return FACE_WEIGHTS;
}
