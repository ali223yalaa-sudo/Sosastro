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

`/workspace/apk-download/index.html` is a small Arabic download landing page
(links to both APKs). Public URL: https://work-1-sjgrvfcdashedalw.prod-runtime.all-hands.dev/
(→ `/Astro.apk`, `/Astro-play.apk`, or `/` for the landing page).

### The download server must survive restarts

Do **not** use `python -m http.server` here. It does not support HTTP Range
requests (a 200 MB APK on a mobile network that gets interrupted has to start
over, and clients often give up) and one `ConnectionResetError` kills the whole
process — which is exactly how the link went dead once.

Instead:

```bash
/workspace/apk-download/start.sh     # starts server.py on :12000 (Range-capable)
setsid nohup /workspace/apk-download/watchdog.sh &   # keeps it alive
```

- `server.py` — threaded, Range-aware, ignores client disconnects.
- `watchdog.sh` — polls the *port* every 5 s (not a PID file: PIDs get reused
  after a sandbox restart, which once made a PID check report "alive" while the
  server was dead) and restarts the server when it stops answering.
- Both live in `/workspace`, so they survive a root-fs wipe; only the running
  processes are lost, and the watchdog restarts the server. After a full sandbox
  restart, start the watchdog again (processes do not come back by themselves).
- `start.sh` serves **two** ports: `:12000` and `:12001` (backup). The watchdog
  checks both and restarts them together. Public URLs:
  https://work-1-sjgrvfcdashedalw.prod-runtime.all-hands.dev/ (12000) and
  https://work-2-sjgrvfcdashedalw.prod-runtime.all-hands.dev/ (12001).


### Why the link can still look dead

- The sandbox **idles out after 20 min** (`OH_RUNTIME_IDLE_TIMEOUT_SECONDS=1200`)
  and every process is killed. The watchdog only survives while the sandbox is
  alive, so after a long idle the link needs `start.sh` + the watchdog again.
- `server.py` must send `Content-Disposition: attachment` **only for `.apk`**.
  Sending it for `index.html` makes the browser save the page as a file instead
  of rendering it — the link looks broken even though the server answers 200.
- Files served: `Astro-arm64.apk` (101 MB, recommended), `Astro.apk`
  (universal, 192 MB), `Astro-play.apk` (238 MB).
- A durable fallback is the GitHub release `astro-v1.12.6` on the repo, but the
  repo is **private**, so those asset URLs need authentication (HTTP 404 for
  anonymous users). Keep that in mind before sharing release links.

### Build environment caveats (learned the hard way)

- The container **root filesystem is wiped on restart** (e.g. after an OOM). Only
  `/workspace` survives. So: install the JDK (it lives under `/usr/lib/jvm`, which is
  on `/`) *after* any restart, but keep the Android SDK and `GRADLE_USER_HOME` inside
  `/workspace` so they persist.
- `gradle.properties` asks for `-Xmx6g` (Gradle) + `-Xmx8g` (Kotlin daemon) = 14 GB,
  which OOMs a 15 GB box and triggers the restart. Override on the command line:
  `--max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" -Pkotlin.daemon.jvmargs="-Xmx3g"`.
- `JAVA_HOME`/`ANDROID_HOME` must be exported **inside** the backgrounded script, not
  on the interactive command line — a `nohup ... &` child does not inherit them otherwise.
- `/workspace/build.sh` wraps all of the above; run it and tail `/workspace/build2.log`.

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

## Reels vs Home: the publisher decides, not the aspect ratio

Both screens build their candidate list from the same cache (`LocalCache.notes` +
`LocalCache.addressables` for `VideoVerticalEvent`/`VideoHorizontalEvent`), so the split has
to be explicit. The rule is the **user's choice at publish time**, not the clip's shape:

- A feed post is always NIP-71 normal video (kind 21, `VideoNormalEvent`), whatever the
  orientation. `sendHeader` used to emit kind 22 (`VideoShortEvent`) for any portrait clip,
  which made a portrait *post* a reel and hid it from the feed. Now only `sendReel` creates
  a reel (kind 34236, `VideoVerticalEvent`), and only when the user picks Reel.
- `Event.isVerticalReelVideo()` (ShortsFeedBasis.kt) returns true for kind 34236/22 only;
  kind 21 and 34235 are never reels. A kind:1 post with no declared `dim` still defaults to
  reels (short-form portrait is the common case), but an explicit `dim` decides: portrait →
  Reels, landscape/square → Home. `ShortsFeedFilter` keeps reels, `VideoFeedFilter` drops
  them (`isReelOnly`). Pictures, file headers and ephemeral stories stay Home-only.

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


## Sharing: one Instagram-Reels-style sheet for reels, videos and posts

Every Share entry point now opens the same drawer instead of the old plain
`ShareOptionsBottomSheet`. Entry points: the reel side rail, the feed reaction
row, the note 3-dot menu (`DropDownMenu`) and the chat action sheet
(`ChatMessageActionSheet`). All call `ShareOptionsBottomSheet(note,
accountViewModel, nav, onDismiss)`, which simply delegates to the sheet below —
add `accountViewModel` when you wire a new entry point.

- `amethyst/.../ui/note/share/ShareReelSheet.kt` — the sheet. Draggable
  `ModalBottomSheet` (scrim, handle, rounded top, back/outside dismiss, nav-bar
  and IME insets), a searchable friends row (multi-select ring + check, chosen
  strip), external options (WhatsApp, system share, download, copy link, add to
  story) and an in-app send button with idle/sending/sent/failed states.
- Friends come from `accountViewModel.account.liveKind3Follows`; send is
  disabled offline or when the account is not writeable.
- In-app send posts the reel's `note.toNostrUri()` over NIP-17 DMs
  (`ChatMessageEvent` + `sendNip17PrivateMessage`) to every selected friend. The
  chat renderer already draws the embedded note preview, so no new chat system
  is involved.
- "Add to story" reuses `Account.sendEphemeralStoryFromUrl` (publishes a story
  pointing at the already-hosted URL, no re-upload). "Download" reuses the same
  media saver as the media-actions dialog.
- The sheet never touches player state, so the reel keeps playing while it is
  open and is not restarted on dismiss.

`ShareReaction` in `ReactionsRow.kt` gained an `accountViewModel` parameter;
update its call sites when you move it.

## Creating content: one "+" that opens camera, editor and composer

The Home top bar has a small `+` (`CreateContentButton` in
`amethyst/.../ui/screen/loggedIn/create/`) at the far right — `extraActions` is
placed **after** the search action in `UserDrawerSearchTopBar`'s `actions` row,
so it is the last item. Tapping it opens `CreateContentSheet`, which routes every
entry point to a real flow:

- Post / Reel / Story / Instant → gallery pick → editor → composer, pre-set to the
  matching `ComposeContentType` (`ui/actions/ComposeContentType.kt`). Reels publish
  as NIP-71 vertical video (kind 34236) via `Account.sendReel`; stories/instant use
  `sendEphemeralStory`.
- Live → `Route.NewHlsVideo`. Text post → `Route.NewShortNote`. Drafts → `Route.Drafts`.

The in-app camera and the gallery both feed the same editor and then the shared
`NewMediaView` composer, so there is one edit/publish surface regardless of source.

- `ui/actions/uploads/CameraCaptureScreen.kt` — full-screen CameraX preview with
  photo/video modes, front/back switch, flash (photo) / torch (video) and a
  countdown timer. Needs `androidx.camera:camera-video` (already in
  `gradle/libs.versions.toml`). Captures land in the app's external files dir and
  come back as `SelectedMedia`.
- `ui/screen/loggedIn/create/MediaEditorScreen.kt` — crop (aspect presets),
  rotate, flip, colour filters and draggable text overlays. Photos render to a new
  cache JPEG on "next"; videos pass through unchanged. Both the camera and the
  editor are hosted in a borderless full-screen `Dialog` (`FullScreenDialog`).

## Reels overlays: safe gaps, frosted pill, one-reel snap

Three reels-only rules, all in `shorts/VideoCardCompose.kt`,
`note/ReactionsRow.kt` (`ShortsReelsSideReactions`) and
`note/effects/LiveReactionEffects.kt`:

- **Bottom safe gap.** The reels overlays (side reaction column, author row,
  caption) sit on top of the playing video, so they are padded above the bottom
  bar by `LocalDisappearingScaffoldPadding.calculateBottomPadding() +
  ReelsBottomSafeGap` (18dp), computed once in `VideoCardCompose` and passed to
  both the side column and the bottom column. The outer `DisappearingScaffold`
  already reserves the `AppBottomBar` height; the gap is the extra breathing
  room. Do not pad the side column again on its own.
- **Frosted-glass pill, not loose stickers.** The like facepile and gift trail
  in reels go through `ReelsFloatingGroup` (rounded pill: soft shadow, hairline
  highlight, translucent surface) wrapped in `ReelsFloatingDismissible`, instead
  of the feed's `FloatingDismissible` bare pile — reels cover the content, so a
  single deliberate chip reads cleaner than avatars floating on the frame. The
  pill can be grabbed and flicked away in any direction (`Modifier.reelsDraggable`).
- **One-reel snap.** `rememberReelsSnapFlingBehavior` in `ShortsFeedLoaded.kt`
  wraps the stock `SnapLayoutInfoProvider` and clamps `calculateApproachOffset`
  to ±one item, so a hard flick advances exactly one reel instead of coasting two
  ahead. `rememberSnapFlingBehavior(lazyListState)` is not enough on its own.

## Tests: `nestsClient` `NestsAuthTest` fails on purpose-built padding

`nestsClient:jvmTest` has two pre-existing failures unrelated to `amethyst`:
`NestsAuthTest` decodes the NIP-98 token with Kotlin's default padded `Base64`,
but `HTTPAuthorizationEvent.rawToken()` emits base64url **without** padding (see
the BUD-01/02 note above). The pre-push hook runs the whole test suite and will
block a push until these are fixed; fix the test to decode with
`Base64.UrlSafe.withPadding(PaddingOption.ABSENT)`, or push a feature branch with
`--no-verify` and disclose it.

## Calls (NIP-AC WebRTC): TURN is the reliability bottleneck

Call signaling lives in `commons/.../nipACWebRtcCalls/CallManager.kt` and is
already robust: `processedEventIds` dedups by inner event id, terminal events
(hangup/reject) are never dropped for age, and offers/answers are re-published
`SIGNAL_RETRANSMIT_COUNT` times while the call stays in the matching state
(receivers dedup, so replays are safe).

The remaining "call sometimes fails to connect" cause is ICE/TURN, not
signaling:

- The built-in `IceServerConfig.defaultTurnServers` point at
  `turn:openrelay.metered.ca` — the old Metered **free** relay. That host is
  dead: `openrelay.metered.ca` resolves to `192.0.2.1` (RFC 5737 TEST-NET) and
  never answers. The same is true for `staticauth.openrelay.metered.ca` and
  `relay.metered.ca`. `buildIceServers()` logs a warning when it falls back to
  these.
- `standard.relay.metered.ca` is reachable but rejects unauthenticated
  `openrelayproject` allocations (400), so the old shared credentials no longer
  work. A real TURN account/credential is required.
- Cloudflare Realtime TURN needs a **TURN Key ID** plus an API token that has
  the Calls/TURN permission, then credentials are minted via
  `POST /accounts/{id}/calls/turn_keys` + `.../credentials/generate-ice-servers`.
  The token in `/workspace/.cf_turn_token` verifies as active but has **no**
  Calls permission (`/accounts/{id}/calls/turn_keys` → Authentication error), so
  it cannot be used. Do not try to generate credentials with it.

Consequence: without a working TURN, calls connect only when both peers can
reach each other directly (LAN, or a non-symmetric NAT STUN can traverse) and
fail across symmetric NATs / restrictive firewalls. Configure a TURN server in
Settings → Calls → Custom TURN servers to fix this. Verify a TURN server
actually works with a STUN/TURN allocate probe before trusting it.
