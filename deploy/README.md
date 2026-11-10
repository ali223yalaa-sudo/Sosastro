# Astro relay + media infrastructure (Cloudflare)

This directory records the Cloudflare deployment configuration for the Astro
backend so it is not lost when a local sandbox is wiped. The live workers are:

| Worker        | Host                                             | What it is                          |
| ------------- | ------------------------------------------------ | ----------------------------------- |
| `astro-relay` | `astro-relay.hmzzhproxy29127.workers.dev`        | Nostr relay (nosflare, Workers+D1)  |
| `astro-media` | `astro-media.hmzzhproxy29127.workers.dev`        | Blossom media server (R2 + D1)      |

Account id: `906ff45b1e21cc77acea88c7780c262c`.

## How it is built

Both workers are thin forks of upstream projects, cloned locally and patched:

```bash
# Relay (nosflare)
git clone --depth 1 https://github.com/Spl0itable/nosflare.git
# apply the settings in nosflare/config.patch.md, then:
npx wrangler deploy

# Media (blossomflare)
git clone --depth 1 https://github.com/iqbqioza/blossomflare.git
# apply the settings in astro-media/wrangler.jsonc, then:
npx wrangler deploy
```

Deploy needs `CLOUDFLARE_API_TOKEN` (scoped to Workers, D1 and R2) and
`CLOUDFLARE_ACCOUNT_ID` in the environment. The token is a secret and is
**never** stored in this repository.

## Important history

The relay was first deployed behind a private gateway path
(`/gw-<token>`), and the app was pointed at that path. Cloudflare answered
that WebSocket handshake with HTTP 400, so the client could neither read nor
publish and the feed looked empty. The gateway was removed: the worker now
accepts the WebSocket on the root path and `Constants.amethystHome` points
there. See the commit "point app at the working relay URL".

The relay is currently an **open** relay (`AUTH_REQUIRED = false`): anyone
who knows the URL can read and write. Set `AUTH_REQUIRED = true` in
`src/config.ts` if that should change.
