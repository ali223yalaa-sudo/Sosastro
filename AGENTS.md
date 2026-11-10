# AGENTS.md — Astro (fork of Amethyst)

Astro is a private fork of [Amethyst](https://github.com/vitorpamplona/amethyst),
a Nostr Android client, rebranded as **Astro**. Application id and package remain
`com.vitorpamplona.amethyst`.

## Product direction

The feed should feel like Instagram: no manual filters. Content is auto-ranked by
**language + location** with priority **Iraqi → Arab → global**.

- **Home** shows only media (photos + videos). Text-only notes are still reachable
  through the **Texts** chip.
- **Reels** is a single tab that merges short and long videos into one stream.
- Auto-ranking is client-side (see `AstroRegionRank`), so it works without a
  redeployed algo-relay.

## Build environment

- JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`). `openjdk-17` is not available on
  Debian 13 (trixie); use 21.
- Android SDK at `/workspace/android-sdk` (set `ANDROID_HOME`; persistent across sessions).
  `compileSdk`/`targetSdk` = 37, `minSdk` = 26 (see `gradle/libs.versions.toml`).
- Gradle wrapper downloads Gradle 9.4.1 on first run.

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/workspace/android-sdk
./gradlew :amethyst:compilePlayDebugKotlin      # fast compile check
./gradlew :amethyst:testPlayDebugUnitTest       # unit tests
./gradlew :amethyst:assemblePlayDebug           # debug APKs
./gradlew :amethyst:spotlessCheck               # formatting (CI enforces this)
```

## Known build blocker: missing JitPack artifact (fixed permanently)

`com.github.vitorpamplona.compose-richtext:richtext-commonmark-android` at commit
`f92ef49c9d` returns 404 from JitPack — the KMP base modules exist, but the Android
variant of that one artifact was never published. Without it, `:commons` fails with
`Unresolved reference 'BasicMarkdown'`.

Fix (committed, no init script needed): the single missing artifact is vendored in
`gradle/local-maven/`, and `settings.gradle.kts` declares that directory as a Maven
repo **before** jitpack.io so the local copy wins for that one coordinate. Only the
`.aar` (30 KB) and its `.pom` are stored; everything else still comes from JitPack.

Do not "fix" this by pointing `markdown` at JitPack's rebuilt tag
`main-f92ef49c9d-1`: JitPack publishes that tag's internal metadata as
`main-SNAPSHOT`, so Gradle rejects it with `inconsistent module metadata`.

If JitPack ever publishes the missing Android artifact for `f92ef49c9d`, delete
`gradle/local-maven/`, remove the repo line in `settings.gradle.kts`, and drop the
`!gradle/local-maven/**/*.aar` rule from `.gitignore`.

## Code conventions

- Kotlin formatting is enforced by Spotless/ktlint. Run `:amethyst:spotlessCheck`.
- `preBuild` depends on `spotlessApply`, so any build silently reformats tracked
  sources. Two files in `:quartz` are stored with CRLF endings in git; a build
  rewrites them to LF and leaves them dirty. This is pre-existing and unrelated to
  feature work — `git checkout -- quartz/` to discard if you did not mean to touch them.
- Feed filters live under `.../ui/screen/loggedIn/<feed>/dal/`. Each `sort()`
  returns an ordered `List<Note>`.
- Ranking helpers live in `.../ui/dal/` (`DefaultFeedOrder.kt`,
  `AstroRegionRank.kt`).

## Feed ranking needs the fetch layer, not just the sort

`AstroRegionRank` can only re-tier notes that already reached `LocalCache`. If the
REQ never asks for Arab content, the cache holds only foreign posts and no amount of
client-side ranking will surface Iraqi/Arab notes. Nostr has no language/country
filter, so the only lever is the `#t` (hashtag) tag.

- `Constants.REGION_QUERY_HASHTAGS` is the shared Arab/Iraqi hashtag seed.
- `astroRegionHashtagFilter(...)` (`ui/dal/AstroRegionQuery.kt`) turns it into a
  `RelayBasedFilter`; it is OR-ed into the Global and AllFollows home fetches and the
  Shorts/Video global fetches.
- `AllFollowsFeedFlow.convert` unions the seed into the feed's `hashtags`. This matters
  because `AllFollowsByOutbox/ProxyTopNavFilter.match` drops any fetched note that is
  not from a followed author **or** carrying a followed hashtag — seeding only the REQ
  without also seeding `match` would fetch the notes and then throw them away.
- `AstroRegionRank.tier` reads `SearchableEvent.indexableContent()` (title + content),
  not just `content`: NIP-71 videos keep the title in a `title` tag, so a `content`-only
  check classified almost every Arabic reel as global.
- The interest path (`usesAstroInterestOrder()` = Middle East / Trending) re-applies
  `astroRegionRanked()` after `ServedFeedOrder`, otherwise the ranked list skipped it.

The personal ME relay and algo-relay live on a Tailscale host
(`desktop-r082364.tail4b3c94.ts.net`) that does not resolve on the public internet, so
those paths contribute nothing for normal users. Public-relay seeding is what makes the
feed work.

## Island mode (Instagram-style) — UI changes

Goal: one owned relay, everything hidden, a single Instagram-like feed. No relay or
region ever appears in the UI.

- **Feed tabs** are exactly two: "For You" and "Following".
  - `TopNavFilterState.astroOnlyRoutes` (a `StateFlow<ImmutableList<FeedDefinition>>`)
    holds `[globalFollow, allFollows]` and is the ONLY option list rendered by
    `HomeTopBar` and `ShortsTopBar` (they used to read `kind3GlobalPeopleRoutes`).
  - `globalFollow` (code `TopFilter.Global`) is relabelled `follow_list_for_you`;
    `allFollows` (code `TopFilter.AllFollows`) is relabelled `follow_list_following`.
  - Default home tab is `TopFilter.Global` (`LocalPreferences.loadFollowListPrefs`),
    and `migrateAstroFilter(...)` folds any legacy persisted value (Europe / MiddleEast
    / Trending / AroundMe / relay chip) to `TopFilter.Global` so the spinner always has
    a match. Same fold is applied to `shorts`.
  - Region/relay chips are gone because they are simply absent from `astroOnlyRoutes`
    — do not re-add them there.
- **Bottom bar is unchanged** (HOME / SHORTS / MESSAGES / NESTS / PROFILE). The user
  explicitly asked NOT to touch it.
- **Ranking**: `usesAstroInterestOrder()` stays `MiddleEast || Trending` (the algo relay
  is unreachable for end users), so "For You"/"Following" use the client-side
  `astroRegionRanked()` tier: Iraq → other Arab → world, then `diversifyByAuthor()`.
  This is why "For You" is `TopFilter.Global`, not `TopFilter.MiddleEast`.
- **Relay UI is hidden everywhere**:
  - Drawer: `IconRowRelays` call removed (`DrawerContent`).
  - Settings: relay setup + relay-auth rows removed (`SettingsCatalogBuilder`).
  - Profile: `ProfileTab.Relays` not added (`ProfileScreen`).
  - Search: relay result rows + LOCAL/RELAYS source toggle removed (`SearchScreen`).
  - Note text: `ClickableRelayUrl` renders nothing; `StaleRelayHint` removed from
    `NoteCompose`.
  - Broadcast action disabled at the source: `AccountViewModel.canBroadcast()` returns
    `false`, so the "Broadcast" row disappears from every menu at once.
- **Media**: `DEFAULT_MEDIA_SERVERS` is only `ServerName("Astro Media", Constants.ASTRO_MEDIA_BASE)`.
  `AstroMediaAnalysisClient.analyze()` no longer calls the Tailscale algo host — it
  returns `Response(status = "done")` locally, so uploads never depend on it.
- **Relay gateway**: the app must use the relay worker **root** URL
  (`wss://astro-relay.<...>.workers.dev/`). The `/gw-<token>` path that an earlier
  build used is **dead**: Cloudflare rejects the WebSocket handshake with
  `HTTP 400 Invalid request`, so the client could neither read nor publish and the
  feed stayed empty even though the user's own posts existed. The root path accepts
  the WebSocket and stores/serves events (verified end-to-end: publish →
  `["OK", id, true, "Event saved successfully"]` → read-back). See
  `Constants.amethystHome`.
- Arabic is supported by the existing `values-ar-rSA` locale (no `values-ar` needed).

## Working rules

- **Build** (verified working): JDK 21 + Android SDK. `local.properties` has `sdk.dir=/workspace/android-sdk` (persistent; `/tmp` is wiped between sessions).
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export ANDROID_HOME=/workspace/android-sdk ANDROID_SDK_ROOT=/workspace/android-sdk
  ./gradlew :amethyst:assembleDebug --no-daemon --console=plain
  ```
  Needs `platforms;android-37.0` (note the `.0` — plain `android-37` does not exist) and
  `build-tools;37.0.0`. Outputs: `amethyst/build/outputs/apk/{fdroid,play}/debug/*-universal-debug.apk`.

## Download server (APK hosting)

`/tmp` is volatile and gets wiped between sessions, which kills any server or files kept
there. Host the APKs from `/workspace/apk-download` (persistent), served on port 12000:

```bash
cp amethyst/build/outputs/apk/fdroid/debug/amethyst-fdroid-universal-debug.apk /workspace/apk-download/Astro.apk
cp amethyst/build/outputs/apk/play/debug/amethyst-play-universal-debug.apk      /workspace/apk-download/Astro-play.apk
/workspace/apk-download/start.sh          # idempotent restart of the :12000 server
```

Public URL: https://work-1-mdelmuvyygotiksw.prod-runtime.all-hands.dev/ (→ `/Astro.apk`,
`/Astro-play.apk`).

- Prefer disabling at the single source (e.g. `canBroadcast`) over editing every call site.
- Do NOT change feed ordering / interest / what-shows-where without asking the user first.

## Chat surfaces: no relay/paging status text

Relay reach and history-paging state must never be shown in chats. Three shared components are
neutralized with an early `return` (Astro-style island mode), so every caller is covered at once:

- `commons/.../ui/feeds/DmHistoryLoadingCard.kt` — `DmHistoryLoadingCard` ("Some relays didn't
  respond", "Older … messages", per-relay history).
- `commons/.../ui/feeds/RelayReachMarker.kt` — `RelayReachMarkers` ("N relay · loaded since …",
  "Loading:"/"Fully loaded:" dividers).
- `amethyst/.../chats/feed/LoadingReplyNote.kt` — `LoadingReplyNote` (inline "searching history /
  N relays unreachable · tap to see which").

Their string resources in `commons/.../composeResources/values/strings.xml`
(`chats_history_*`) are left in place but unreferenced at runtime.

## Chat look: 2027 refresh

Modern messaging surfaces. Keep it consistent across every chat type (DMs, channels, marmot
groups, nests, thread replies) — they all share these components:

- Bubble shapes (single source of truth): `commons/.../commons/ui/theme/ChatTheme.kt`
  (`ChatBubbleShape*`) — 20dp corners with an 8dp grouped edge, 6dp tail.
- Own-message gradient + press scale: `amethyst/.../chats/feed/layouts/ChatBubbleLayout.kt`
  (vertical `Brush` painted under a transparent `Surface` so the shape still clips).
- Composer: floating rounded pill. Shared colors set at every call site
  (`surfaceContainerHigh` focused / `surfaceContainer` unfocused) + `EditFieldBorder` 26dp.
- Send button: `amethyst/.../chats/utils/ThinSendButton.kt` — gradient circle, lifts when active.
- Day/subject divider: `amethyst/.../chats/feed/layouts/ChatDivisor.kt` — centered translucent pill.
- Wallpapers: `amethyst/src/main/res/drawable-nodpi/chat_bg_*.png`, 15 modern dark abstract
  images generated by `/workspace/gen_wallpapers.py` (deterministic; palette-quantized to ~1MB).
  Re-run that script to regenerate; keep the ids in sync with `MessengerChatTheme.kt`.
- Conversation list row: `amethyst/.../ui/layouts/ChatHeaderLayout.kt` (padding 14x11, 14dp
  avatar gap, 5dp title/preview gap) + inset divider in `chats/rooms/feed/ChatroomListFeedView.kt`.

Design tokens are grounded in the Material 3 shape scale (authoritative source:
`androidx/compose/material3/tokens/ShapeTokens.kt`): ExtraSmall 4dp, Small 8dp, Medium 12dp,
Large 16dp, LargeIncreased 20dp, ExtraLarge 28dp, ExtraExtraLarge 48dp. Chat bubbles use
LargeIncreased (20dp) for the open corners and Small (8dp→6dp cluster edge) for the tight ones.

## Known issue: Home feed and Reels show the same videos

Both screens build their candidate list from the same cache (`LocalCache.notes` +
`LocalCache.addressables` for `VideoVerticalEvent`/`VideoHorizontalEvent`) and used the
same acceptance rule (playable video), so every video in the Home feed also qualified for
Reels.

Fix (applied): split by orientation. `Event.isVerticalReelVideo()` (ShortsFeedBasis.kt)
classifies a video as portrait; `ShortsFeedFilter` keeps only portrait videos, and
`VideoFeedFilter` drops them (`isReelOnly`). NIP-71 vertical/short kinds are always reels,
horizontal is never, normal/kind:1 use the declared `dim` (portrait when unknown for
kind:1). Pictures, file headers and ephemeral stories stay Home-only.

## Media uploads: the server enforces an allowlist

`astro-media` (blossomflare) defaults to `upload_policy = allowlist`; with an empty
allowlist every upload fails with HTTP 403 "uploader not allowed" — independent of the
app. Set it to open for an open-to-all server:

```bash
cd /workspace/astro-cf/blossomflare
npx wrangler d1 execute astro-media-db --remote \
  --command "INSERT INTO settings (key,value,updated_at) VALUES ('upload_policy','open',strftime('%s','now')) ON CONFLICT(key) DO UPDATE SET value='open', updated_at=strftime('%s','now');"
```

Other server limits: max blob 25 MiB (`MAX_UPLOAD_BYTES`, hard-capped in blobs.ts).
Blossom auth must be kind 24242 with `t`/`expiration`/`x` tags (BUD-01/02).

## Media uploads: Authorization token must be base64url (no padding)

`BlossomAuthorizationEvent.rawToken()` and `HTTPAuthorizationEvent.rawToken()` used to
emit Kotlin's default `Base64` (standard alphabet, padded). BUD-01/BUD-02 and blossomflare
require **base64url without padding**; a standard/padded token is rejected with HTTP 401
`invalid authorization token` before any policy check. Both encoders now use
`Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)`. The in-app verifier
(`Nip98AuthVerifier`) normalizes `+`/`/` to `-`/`_` and allows optional padding so it still
accepts tokens from older clients.

