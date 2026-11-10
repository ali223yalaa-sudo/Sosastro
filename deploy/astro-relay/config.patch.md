# nosflare settings applied for Astro

Patches applied on top of upstream `Spl0itable/nosflare` before deploy.

## `wrangler.toml`

See `../astro-relay/wrangler.toml`. Key changes from upstream:

- `name = "astro-relay"`
- D1 binding `RELAY_DATABASE` → database `astro-relay-db`
  (`757e97ca-cf33-4bc8-b040-51251c23dd77`)

## `src/config.ts`

```ts
// Pay to relay — DISABLED (private personal relay, not a paid service)
export const relayNpub = "npub16jdfqgazrkapk0yrqm9rdxlnys7ck39c7zmdzxtxqlmmpxg04r0sd733sv";
export const PAY_TO_RELAY_ENABLED = false;
export const RELAY_ACCESS_PRICE_SATS = 212121; // unused while PAY_TO_RELAY_ENABLED is false

// NIP-42 Authentication — OPEN relay: anyone can read/write without AUTH.
// Set to true only if you later want to restrict access.
export const AUTH_REQUIRED = false;
export const AUTH_TIMEOUT_MS = 600000;

export const relayInfo: RelayInfo = {
  name: "Astro Relay",
  description: "Astro's open Nostr relay — everyone can post, read and talk",
  pubkey: "d49a9023a21dba1b3c8306ca369bf3243d8b44b8f0b6d1196607f7b0990fa8df",
  contact: "admin@astro",
  // ...
};
```

## `src/relay-worker.ts`

The private gateway path was removed. The worker accepts the WebSocket on the
root path again (upstream behaviour):

```ts
// Main endpoints
if (url.pathname === "/") {
  if (request.headers.get("Upgrade") === "websocket") {
```

## D1 databases

| Name             | Id                                     | Used by      |
| ---------------- | -------------------------------------- | ------------ |
| `astro-relay-db` | `757e97ca-cf33-4bc8-b040-51251c23dd77` | relay        |
| `astro-media-db` | `fcc36282-f7e3-488e-b0b3-85b08fb47fd7` | media server |

## R2 buckets

| Bucket        | Used by      |
| ------------- | ------------ |
| `astro-media` | media server |
