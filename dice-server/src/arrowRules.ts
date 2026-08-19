/**
 * Shared Arrow-mode jump rules (1v1 + 4p Arrow DO stacks).
 * Classic rooms must never import this.
 */

/** Absolute track cells: land → move exactly one cell forward. */
export const ARROW_TRACK_JUMPS: Record<number, number> = {
  5: 6,
  18: 19,
  31: 32,
  44: 45,
};

/** Relative: last track cell before home stretch → first home cell. */
export const HOME_ENTRY_FROM_STEP = 50;
export const HOME_ENTRY_TO_STEP = 51;
