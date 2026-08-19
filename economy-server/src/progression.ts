/**
 * Ludo player level — steep curve, server-side XP only.
 * Level 1 = 0 XP. Each next level needs more XP than the last (base 900, growth 1.42).
 */

export type ProgressSnapshot = {
  pubkey: string;
  level: number;
  totalXp: number;
  xpIntoLevel: number;
  xpForNextLevel: number;
};

/** XP cost to go from level (n-1) to level n. Level 1 costs 0. */
export function xpStepForLevel(level: number): number {
  if (level <= 1) return 0;
  return Math.floor(900 * Math.pow(1.42, level - 2));
}

/** Total XP required to reach [level] (at level start). */
export function totalXpForLevel(level: number): number {
  if (level <= 1) return 0;
  let sum = 0;
  for (let l = 2; l <= level; l++) sum += xpStepForLevel(l);
  return sum;
}

export function levelFromTotalXp(totalXp: number): number {
  let level = 1;
  while (totalXpForLevel(level + 1) <= totalXp) level++;
  return level;
}

export function progressSnapshot(pubkey: string, totalXp: number): ProgressSnapshot {
  const level = levelFromTotalXp(totalXp);
  const floor = totalXpForLevel(level);
  const ceiling = totalXpForLevel(level + 1);
  return {
    pubkey,
    level,
    totalXp,
    xpIntoLevel: totalXp - floor,
    xpForNextLevel: Math.max(1, ceiling - floor),
  };
}

/** Authoritative XP per action (game worker / verified hooks). */
export const XP_REWARDS: Record<string, number> = {
  ludo_match_played: 25,
  ludo_piece_captured: 8,
  ludo_piece_home: 12,
  ludo_match_win: 120,
  ludo_match_lose: 15,
  council_visit: 10,
  profile_avatar_set: 50,
  daily_login: 20,
};

export async function getProgress(db: D1Database, pubkey: string): Promise<ProgressSnapshot> {
  const row = await db
    .prepare(`SELECT total_xp FROM player_progress WHERE pubkey_hex = ?`)
    .bind(pubkey)
    .first<{ total_xp: number }>();
  return progressSnapshot(pubkey, row?.total_xp ?? 0);
}

export async function awardXp(
  db: D1Database,
  args: {
    pubkey: string;
    action: string;
    refId: string;
    now: number;
    xpOverride?: number;
  },
): Promise<{ ok: true; progress: ProgressSnapshot; awarded: number } | { ok: false; reason: string }> {
  const xp = args.xpOverride ?? XP_REWARDS[args.action];
  if (!xp || xp <= 0) {
    return { ok: false, reason: "unknown_action" };
  }

  const eventId = crypto.randomUUID();
  const insert = await db
    .prepare(
      `INSERT INTO xp_events (id, pubkey_hex, action, xp, ref_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    )
    .bind(eventId, args.pubkey, args.action, xp, args.refId, args.now)
    .run()
    .catch(() => null);

  if (!insert || !insert.success || insert.meta.changes === 0) {
    return { ok: false, reason: "duplicate" };
  }

  await db
    .prepare(
      `INSERT INTO player_progress (pubkey_hex, total_xp, created_at, updated_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(pubkey_hex) DO UPDATE SET
         total_xp = total_xp + excluded.total_xp,
         updated_at = excluded.updated_at`,
    )
    .bind(args.pubkey, xp, args.now, args.now)
    .run();

  const row = await db
    .prepare(`SELECT total_xp FROM player_progress WHERE pubkey_hex = ?`)
    .bind(args.pubkey)
    .first<{ total_xp: number }>();

  return {
    ok: true,
    awarded: xp,
    progress: progressSnapshot(args.pubkey, row?.total_xp ?? xp),
  };
}
