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

`/workspace/apk-download` (persistent) is the single source of truth for hosting.
Publish the newest build with the helper (it picks the newest fdroid APKs and
copies them under stable names):

```bash
/workspace/apk-download/publish.sh    # -> astro-dm.apk (universal) + astro-dm-arm64.apk
/workspace/apk-download/start.sh      # idempotent restart of the :12000/:12001 server
```

`/workspace/apk-download/index.html` is the Arabic landing page; its links must
match what `publish.sh` writes (`astro-dm.apk`, `astro-dm-arm64.apk`). Public URL:
https://work-1-iswqtkfbvispqkrh.prod-runtime.all-hands.dev/ (port 12000) and
https://work-2-iswqtkfbvispqkrh.prod-runtime.all-hands.dev/ (port 12001).

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
- Files served: `astro-dm-arm64.apk` (arm64, ~157 MB), `astro-dm.apk`
  (universal, ~210 MB). Older archives (`astro-final-*.apk`, `astro-calls-fixed-*.apk`)
  stay in the dir but are no longer linked from `index.html`.
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
  It now also runs `apksigner verify` on every output APK and **fails** if any is
  unsigned.
- **APK signing**: debug builds sign with `keystore/debug.keystore` (committed;
  pinned in `amethyst/build.gradle.kts`). Do **not** rely on `~/.android/debug.keystore`
  — it is on the wiped root fs, and when it is absent Gradle emits APKs with *no*
  signature at all ("الحزمة غير صالحة" / `Missing META-INF/MANIFEST.MF`), which
  install on nothing. If APKs ever fail to install, check `apksigner verify` first.

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

## Tests: `nestsClient` `NestsAuthTest` decoded with the wrong Base64 alphabet

`nestsClient:jvmTest` used to fail with two errors in `NestsAuthTest`:
it decoded the NIP-98 token with Kotlin's default padded `Base64`, but
`HTTPAuthorizationEvent.rawToken()` emits base64url **without** padding (see
the BUD-01/02 note above). Fixed by decoding with
`Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)`. The pre-push hook
runs the whole suite, so any new mismatch here blocks a push.

## Island-mode test drift (fixed once; do not re-introduce)

`Constants.TEMP_HOME_RELAY_ONLY = true` collapses every aggregated relay set
onto the single owned relay (`amethystHome`). Two tests were written against
the pre-island passthrough behaviour and had drifted red:

- `BlockedRelayFilteringClientTest` asserted raw relay keys pass through. In
  island mode `subscribe`/`count`/`publish` all land on `amethystHome`; the
  blocked relay's *filters* are still stripped first. `Constants.homeRelayOnly`
  now guards the empty set (mirroring `homeRelayOnlyMap`) so an all-blocked
  publish yields no relays instead of falling through to home.
- `NestViewModelTest` asserted `onStageNow` defaults to `true`; the seat-driven
  model defaults it to `false` (a fresh joiner is audience).

## Calls (NIP-AC WebRTC): glare and headless delivery

Beyond TURN (below), two things made calls flaky:

- **Initial-call glare** — both peers dial each other at the same instant.
  `CallManager.onSignalingEvent` used to reject the incoming offer with "busy"
  whenever it was already `Offering`, killing both half-calls. It now resolves
  the collision deterministically (lower pubkey wins): the winner keeps its own
  offer and rejects the peer's; the loser abandons its offer, adopts the
  incoming one, and emits `CallSessionEvent.AutoAcceptIncomingOffer`, which the
  Activity-scoped `CallSession` turns into `accept(sdpOffer)`. `CallSession.accept()`
  disposes any stale `PeerConnection` to that caller first so the abandoned
  outgoing offer does not leak native resources. `SIGNAL_RETRANSMIT_COUNT`/
  `_DELAY_MS` are sized (12 × 2.5s ≈ 27.5s) to cover the whole caller invite
  window so an offline/asleep callee still gets a copy on reconnect.
- **Headless delivery** — keeping the relay socket alive (`NotificationRelayService`)
  is not enough. The only gift-wrap REQ was the Compose-tree
  `AccountFilterAssemblerSubscription`, torn down with the Activity, so with no
  foreground screen nothing was ever requested and neither the call ring nor the
  DM notification could fire. `AlwaysOnGiftWrapSubscription` (kinds 1059/21059 on
  the account's DM relays, re-issued when the relay set changes) supplies the
  missing REQ, wired into `AlwaysOnNotificationServiceManager`; the settings
  default for `alwaysOnNotificationService` is now `true`.

## Calls (NIP-AC WebRTC): SDP async races, headless lifecycle, double-ring

Three more call defects, fixed together (commit `fix(calls): resolve stuck-connecting race, headless lifecycle, double-ring`):

- **Stuck at "connecting" (ICE/answer async race).** libwebrtc applies
  `setRemoteDescription` asynchronously. The old code called `createAnswer()` and
  `flushPendingIceCandidates()` immediately after `setRemoteDescription(...)`
  returned, so every buffered ICE candidate was rejected natively and both peers
  hung at "connecting" with no audio. Fix: `WebRtcCallSession.setRemoteDescription`
  takes an `onSet` completion callback fired only from the native
  `onSetSuccess`; `createAnswer` + the ICE flush run from inside it. Symmetrically,
  an outgoing offer/answer is signalled to the peer only after
  `setLocalDescription` completes (`localDescriptionObserver`), or the reply is
  rejected as wrong-state. `PeerSession.setRemoteDescription` gained the same
  `onSet` parameter. Regression test:
  `PeerSessionManagerTest.routeAnswerDefersIceFlushUntilRemoteDescriptionApplied`.

- **Headless lifecycle.** `CallManager` used to be owned by the Activity-scoped
  `AccountViewModel`, so it died with the Activity and an incoming call could not
  ring/answer once the UI was gone. It is now owned by `Account` (app-scoped,
  cancelled only via `Account.release()` on account removal), wired into the
  signaling processor at construction. `AppModules` publishes the account to
  `CallSessionBridge` at process start and starts `HeadlessCallCoordinator`,
  which rings and raises `CallForegroundService` when no call UI is mounted.

- **Double-ring.** The headless coordinator was gated on `accountViewModel != null`,
  but a cold-started `CallActivity` builds its own lazy (headless) ViewModel, so
  the field stayed null and BOTH the coordinator and `CallSession` rang with
  separate `CallAudioManager` instances. Fix: `CallSessionBridge.isCallUiMounted`
  (a process-level `StateFlow`) is set true in `CallActivity.onCreate` and false
  in `onDestroy`; the coordinator observes it (combined with call state) and stops
  its fallback ring the moment the UI mounts. Do not re-gate on the ViewModel.

## Calls (NIP-AC WebRTC): TURN

Call signaling lives in `commons/.../nipACWebRtcCalls/CallManager.kt` and is
already robust: `processedEventIds` dedups by inner event id, terminal events
(hangup/reject) are never dropped for age, and offers/answers are re-published
`SIGNAL_RETRANSMIT_COUNT` times while the call stays in the matching state
(receivers dedup, so replays are safe).

ICE/TURN is resolved at call time by `IceServerConfig.buildIceServers()`:

1. user-configured TURN servers (Settings → Calls → Custom TURN servers), else
2. `MeteredTurnProvider.fetch()` — a live `GET` to the Metered credentials
   endpoint, cached 6h, returning fresh `standard.relay.metered.ca` credentials
   (UDP + TCP/TLS), else
3. `IceServerConfig.defaultTurnServers` — static Metered credentials on
   `standard.relay.metered.ca` (ports 80/443, TCP + TLS) for offline-at-start.

STUN (`stun.l.google.com`, `stun1.l.google.com`, `stun.cloudflare.com`) is
always prepended. The historical `openrelay.metered.ca` free relay is dead
(resolves to TEST-NET `192.0.2.1`); do not reintroduce it. The Metered
credential-scoped API key in `MeteredTurnProvider` is safe to ship (it can only
mint TURN credentials). If calls still fail across symmetric NATs, verify a TURN
server with a STUN/TURN allocate probe before trusting it.

## Nests (voice rooms): gifting (TRTC-style, no backend)

The room gift system replaces the star-wallet `GiftSupportSheet` flow inside
voice rooms with a self-contained, backend-free gifting path modelled on
TRTC/TUILiveKit's two-component design:

- Selection panel — `amethyst/.../nests/room/gifts/RoomGiftPanel.kt`
  (the `GiftListView` analogue). A modal bottom sheet with tier tabs, a gift
  grid, a quantity stepper and a send button. Each cell draws the gift's real
  icon (`GiftAssets.kt` → `assets/gifts/icons/<gift>.png`, rendered from
  Twemoji) with the emoji as the pre-decode fallback. The catalog is the local
  constant `RoomGiftCatalog` in
  `commons/.../viewmodels/RoomGiftsState.kt` — no server, no wallet.
- Playback overlay — `amethyst/.../nests/room/gifts/RoomGiftOverlay.kt`
  (the `GiftPlayView` analogue). A transparent full-screen layer mounted in
  `NestFullScreen` ABOVE the stage/chat Column and BELOW the sheets/dialogs.
  It queues bursts one at a time and never intercepts taps. Every gift plays
  its own full-screen SVGA clip (`SvgaGiftPlayer.kt`) and its own `res/raw`
  sound (`GiftSounds` in `GiftAssets.kt`, played once per burst). The clip
  fills the screen exactly as authored (the bundled clips are full-frame), so
  the gift reads as a cinematic takeover rather than a sticker; only the
  sender (or sender→receiver) line overlays it, and nothing is drawn behind
  it. A burst is held for `maxOf(giftBurstMillis(tier), MIN_GIFT_HOLD_MS)`
  (`MIN_GIFT_HOLD_MS = 4.6 s`) so the short 3 s `gen_*` clips do not blink out.
  `RoomGiftTicker` in the same file draws
  the recent-gifts row; it sits in the room's normal column flow directly below
  the mic seats (NOT pinned to the top as an overlay), so the "who sent what"
  chips never cover the room header.

Transport: a gift is a normal NIP-25 kind-7 reaction whose `content` is the
gift emoji and which carries a `["gift", skuId, quantity]` tag. Sending uses
`sendRoomGift(...)` (`RoomGiftSender.kt`) → `signAndComputeBroadcast`, so it
lands in LocalCache and on the user's default relays exactly like a reaction.
Receive path: `ReactionEvent.giftPayload()` distinguishes a gift from a plain
reaction; `NestViewModel.onReactionEvent` skips tagged gifts and the kind-7
collector (`NestRoomEventCollectors.kt`) routes them to
`NestViewModel.onGiftEvent`. `RoomGiftsAggregator` dedups by event id, groups by
RECIPIENT pubkey (room-wide gifts under `""`) and evicts on the same 1-s tick as
reactions/zaps but with the longer `GIFT_WINDOW_SEC` (22 s) so a burst finishes
playing. Tests: `commons/.../viewmodels/RoomGiftsStateTest.kt` (21 tests).

Gift visual identity: a gift's *look* is not hard-coded in the overlay. Each
SKU resolves a `RoomGiftTheme` (accent + accentDeep, a `GiftMotif` from the
12-entry enum, a signed `rayRotation`, a density `variant` 0..2, and a per-gift
layout `seed`) via `RoomGiftSku.theme()` / `giftThemeFor(id)` in
`RoomGiftsState.kt`. `CatalogThemes` builds the map lazily and assigns accents
*greedily* so no two gifts share accent + motif — the coarse signature a viewer
perceives. The overlay tints its scrim/rays/marquee from `theme.accent` and
dispatches `drawMotif(...)`; every particle function takes `seed` so two gifts
sharing a motif *and* variant still scatter differently. Every gift also ships
its own full-screen SVGA clip, its own panel icon and its own `res/raw` sound,
and no two gifts share any of them. When adding a gift: add a clip to
`assets/gifts/`, an icon to `assets/gifts/icons/` and a sound to
`res/raw/` (see the README for licensing), add a `RoomGiftSku` (set `svga`,
`icon` and `sound`), and (optionally) a `SemanticMotifs` entry; the
`everyGiftHasADistinctTheme` + `noTwoGiftsShareAccentAndMotif` +
`everyGiftHasACinematicClip` + `everyGiftHasARealIcon` +
`everyGiftHasItsOwnSound` tests enforce the rest. The per-gift clips are
authored by `/workspace/svga_tools/generate.py` and the sounds by
`/workspace/svga_tools/generate_sounds.py`; renders can be verified headlessly
with `/workspace/verify_gifts.py` (mirrors the canvas math to a PNG contact
sheet; asserts no two gifts render identically).

## Nests (voice rooms): back must dismiss the keyboard first

The room minimizes to PIP on back while connected. Two separate enabled
`BackHandler`s used to own that: one in `NestActivityContent.kt`
(`KeyboardAwareBackHandler(enabled = canMinimize)`) and one in the composer
(`NestEditFieldRow.kt`, focus-gated). Compose runs the most-recently-composed
enabled handler, so the two raced and back could become a no-op while typing —
the reported symptom: type in the room, press back to hide the keyboard, and
nothing happens (or the PIP minimize races the IME close animation and leaves
`Modifier.imePadding()` frozen).

Fix: there is now a **single back owner**, a `BackHandler` in
`NestFullScreen.kt`, gated on `composerFocused || canMinimize`. The composer
reports its focus up via a new `onFocusChanged` callback (it watches its text
field's `interactionSource` through `collectIsFocusedAsState`). The handler
dismisses the keyboard (`focusManager.clearFocus()` + `keyboard.hide()`) and
consumes the press when the composer is focused; otherwise it runs the caller's
minimize-to-PIP (or falls through to `Activity.finish()` when idle). Tracking
focus directly — rather than the IME inset — works on every Android version,
edge-to-edge or not, so `NestActivity` does not need `enableEdgeToEdge()` for
this (its `adjustResize` + `Modifier.imePadding()` handle layout).

Do not add a second enabled `BackHandler` to the room: it will race this one and
re-trap the composer. Tapping empty space also dismisses the keyboard (a
`pointerInput { detectTapGestures }` on the Scaffold content Box), and the
`MicSeatsGrid` is kept mounted at all times — it used to be removed while the
keyboard was up (`if (!imeOpen)`), which churned the column layout and made the
keyboard flicker.

## Nests: open-mic guests could not take a seat (host-only ascent)

Symptom: with the room in open-mic mode, the host could grab an empty
seat instantly but every other user tapped an empty mic and nothing
happened — nobody subscribed to their audio, so the room looked broken.

Root cause lived in `buildParticipantGrid` (`commons/.../viewmodels/ParticipantGrid.kt`),
the pure projection that decides who is "on stage". It only promotes a
peer with `role != PARTICIPANT` when the room is open-mic. But
`RoomParticipantActions.leaveSeat` stamps *every* demoted peer
`PARTICIPANT` (and `HostMicSeatAuthority` mirrors that back to the host
event), so a guest who had ever stepped off a mic carried a stale
`PARTICIPANT` tag forever. When they later self-claimed an open seat,
their `micSeat` presence said "on stage" while their role tag said
"audience" — the role tag won, the guest never entered `participantGrid.onStage`,
`AutoConnectAndTrackSpeakers` never subscribed to their audio, and the
seat looked dead. The host was unaffected because the host's role is
always `HOST`.

Fix: a live self-claim now beats a stale `PARTICIPANT` role. The
open-mic branch is `(role != ROLE.PARTICIPANT || pres.micSeat != null)`,
so `micSeat` + `onstage` is enough to ascend. A guest merely *present*
in an open-mic room (no seat claim) still stays in the audience —
regression tests `openMicGuestWithStaleParticipantRoleStillAscendsOnSeatClaim`
and `openMicGuestWithoutSeatClaimStaysInAudience` in
`commons/.../viewmodels/ParticipantGridTest.kt` lock both directions.

## Nests: ghost members — stale presence replayed from cache

`PresenceCollector` (`NestRoomEventCollectors.kt`) reads kind-10312 presence
from `LocalCache`, which keeps events from earlier sessions and re-emits the
whole matching list on every cache mutation. So a peer who was in the room an
hour ago — and force-closed the app without publishing a `left` — would be
replayed as "present" the moment you rejoin, showing a ghost member who isn't
really there. The chat collector already dropped history from before the
session start; the presence collector now does the same: it ignores any event
older than `PRESENCE_STALE_THRESHOLD_SEC`. That constant was also tightened
from 2 min to 60 s (four missed 15-s `PRESENCE_REFRESH_MS` heartbeats), which
is the same window the `PresenceEvictionTicker` uses to drop a dead peer, so
presence that is replayed and presence that is evicted can't disagree.

## Nests: room opens only when ready + gentle reconnect overlay

`NestActivityContent.kt` now gates the full room UI on the MoQ session
having connected at least once (`hasConnected`, latched from
`ConnectionUiState.Connected`). Before that it shows `RoomConnectingState`
— a centered spinner with the live connect step (`connectingLabel`) and a
Leave button, plus a Retry/Leave card on `ConnectionUiState.Failed`. After
the first connect the room stays mounted across transient drops and
`ConnectionOverlay` draws a translucent "Reconnecting…" card on top, so a
weak-network blip never tears down chat scroll or the mic grid. This is
what stops the "entered but nothing happens" confusion in a weak network.

## Nests: join banner, larger bottom text

- `stage/JoinBanner.kt` renders a transient "X joined the room" chip under
  the mic grid, driven by `NestViewModel.roomNotices` (ENTERED notices),
  auto-fading after ~3.5 s. String: `nest_join_banner` (EN + AR).
- Bottom-of-room text was too small: the composer text + placeholder are
  now 16 sp (`NestEditFieldRow.kt`) and the error status strip is
  `bodyMedium` (`ActionBarStatusStrip` in `NestActionBar.kt`).
