# Amethyst Ludo Economy (Phase 0)

Isolated Cloudflare Worker + D1 ledger for gold/gems and item ownership.

**Hard rule:** This service must never bind or call Ludo `GameRoom` / `Matchmaker` / Arrow Durable Objects.

## Security model (Nostr / Nestro-style, our private network)

We reuse the same **identity + signature discipline** as Nostr apps (and loyalty stacks like Nestro): the user’s **secp256k1 key** is the account. The phone never invents balances; Cloudflare D1 is the source of truth.

| Layer | What we do |
|-------|------------|
| Identity | Hex pubkey from verified NIP-98 event (same key as Amethyst account) |
| Auth | Every private call signs `Authorization: Nostr <base64 kind-27235>` |
| Crypto | Schnorr over event id (NIP-01); id must match canonical serialization |
| Bind | Tags `method` + absolute `u` must match request (anti-proxy forge) |
| Freshness | `created_at` within ±60s |
| Replay | Each auth `event.id` inserted once into `auth_replay` (D1 UNIQUE) |
| Body integrity | POST bodies require `payload` = sha256(body) |
| Abuse | 60 authenticated requests / minute / pubkey |
| Money | Append-only `ledger`; balance updates batched with ledger inserts |
| Ops | `ADMIN_SECRET` timing-safe compare; never in APK |
| Isolation | No Durable Objects shared with Ludo match/rooms |

Phase 0 protects **wallet reads + ledger integrity**. Match tickets / winner payouts come in later phases and will credit **only** from trusted Worker→Worker signals — never from the client claiming “I won”.

## Security checklist (implemented)

- NIP-98 on `/v1/wallet` and `/v1/ledger`
- Signature + event id check (Schnorr)
- ±60s expiry
- Method + absolute URL binding (`PUBLIC_BASE_URL`, not `Host` header)
- Body `payload` hash when POST body present
- D1 anti-replay on auth event ids
- Per-pubkey rate limit
- Append-only `ledger` for every balance change (atomic batch with balance)
- Admin credit requires `ADMIN_SECRET` (Wrangler secret) — not shipped in the APK

## Local

```bash
npm install
npx wrangler d1 create ludo-economy
# put database_id into wrangler.jsonc
npx wrangler d1 execute ludo-economy --remote --file=./migrations/0001_init.sql
npx wrangler secret put ADMIN_SECRET
npm run deploy
```

## Routes

| Method | Path | Auth |
|--------|------|------|
| GET | `/health` | none |
| GET | `/v1/catalog` | none (browse prices) |
| GET | `/v1/wallet` | NIP-98 (+ grants free starters + daily status) |
| GET | `/v1/ledger` | NIP-98 |
| GET | `/v1/daily` | NIP-98 |
| POST | `/v1/daily/claim` | NIP-98 (once / UTC day) |
| POST | `/v1/purchase` | NIP-98 + body hash (server price only) |
| POST | `/v1/match/ticket` | NIP-98 — debit gold ticket |
| POST | `/v1/internal/match/bind` | `x-game-secret` (dice Worker) |
| POST | `/v1/internal/match/payout` | `x-game-secret` (dice Worker on MatchFinished) |
| POST | `/v1/admin/credit` | `x-admin-secret` |
| GET | `/v1/topup/packages` | none (browse packages) |
| POST | `/v1/topup/create` | NIP-98 — create pending order (server amounts only) |
| POST | `/v1/topup/webhook` | `X-Payment-Signature` HMAC (`PAYMENT_WEBHOOK_SECRET`) |
| POST | `/v1/admin/topup/fulfill` | `x-admin-secret` — test/manual settle |

**Phase 4:** client cannot mint gold. Create order → pay via provider (Stripe/Zain later) → webhook/admin fulfills → ledger credit from stored package amounts.

