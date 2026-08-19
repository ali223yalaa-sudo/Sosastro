/** Atomic-ish fixed-window rate limit per bucket (pubkey / IP). */
export async function hitRateLimit(
  db: D1Database,
  bucketKey: string,
  limit: number,
  windowSec: number,
  nowSec: number,
): Promise<{ allowed: boolean; remaining: number }> {
  const row = await db
    .prepare(`SELECT window_start, hit_count FROM rate_limits WHERE bucket_key = ?`)
    .bind(bucketKey)
    .first<{ window_start: number; hit_count: number }>();

  if (!row || nowSec - row.window_start >= windowSec) {
    await db
      .prepare(
        `INSERT INTO rate_limits (bucket_key, window_start, hit_count) VALUES (?, ?, 1)
         ON CONFLICT(bucket_key) DO UPDATE SET window_start = excluded.window_start, hit_count = 1`,
      )
      .bind(bucketKey, nowSec)
      .run();
    return { allowed: true, remaining: Math.max(0, limit - 1) };
  }

  // CAS: only increment while under the ceiling in the same window.
  const bumped = await db
    .prepare(
      `UPDATE rate_limits SET hit_count = hit_count + 1
       WHERE bucket_key = ? AND window_start = ? AND hit_count < ?`,
    )
    .bind(bucketKey, row.window_start, limit)
    .run();
  const changed = (bumped.meta as { changes?: number } | undefined)?.changes ?? 0;
  if (changed < 1) {
    return { allowed: false, remaining: 0 };
  }
  return { allowed: true, remaining: Math.max(0, limit - row.hit_count - 1) };
}
