# AstroRecommend (replaces AlgoRelay ranking)

Astro-owned interest feed on the PC. Same ports as before:
- Listen `:3334` (Funnel → `:8443` → `Constants.astroAlgo`)
- Live ingest from ME strfry only (`ME_RELAY_URL` / default Tailscale URL)
- **No historical backfill** for recommendations

## Surfaces
REQ tags `#astro` = `refresh|more` + `posts|foryou`

| Surface | Kinds (approx) | Client tab |
|---------|----------------|------------|
| `posts` | 1, 6, 16, 30023 | Home notes |
| `foryou` | 20–22, 1063, 34235/6, 30923, 1 | Home «لك» / Stories feed |

One ranked pool per `(user, surface)`. Batch size **5**. Exhausted pool → recent ME fallback (no wrap-to-zero).

## Economic Gemini
- Videos: caption/URL text only (never full upload)
- Images: ≤512KB inline + short JSON schema
- Daily budget (`GEMINI_DAILY_LIMIT`, default 40)
- Caption keyword fallback when quota/API fails → still `status=done`

## Ops
Use `start-algo.bat` (or run `algo-relay.exe`). Env: `GEMINI_API_KEY`, Postgres on `:1984`.
