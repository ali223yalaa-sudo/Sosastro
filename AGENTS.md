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


### Release APK hosting (signed, per-ABI)

Signed **release** APKs are served from `/workspace/apk-share`, not
`/workspace/apk-download` (that dir holds the older debug/archive builds).
`apk-share/server.py` is the Range-capable server; run it and the watchdog the
same way as above:

```bash
(cd /workspace/apk-share && setsid nohup python3 server.py 12000 12001 >>server.log 2>&1 &)
setsid nohup /workspace/apk-share/watchdog.sh &   # polls both ports, restarts on death
```

Served files (stable names): `astro-1.12.6-universal.apk` (177 MB),
`astro-1.12.6-arm64-v8a.apk` (133 MB), `astro-1.12.6-armeabi-v7a.apk` (123 MB),
plus `SHA256SUMS.txt`. `index.html` links to exactly these names.

Release signing (Gradle emits the release variant unsigned):

```bash
BT=/workspace/android-sdk/build-tools/37.0.0
PW=$(cat /workspace/keys/astro-release-password.txt)   # alias: astro
for ABI in arm64-v8a armeabi-v7a universal; do
  IN=$(ls amethyst/build/outputs/apk/fdroid/release/*${ABI}*unsigned.apk)
  $BT/zipalign -f -p 4 "$IN" /tmp/z.apk
  $BT/apksigner sign --ks /workspace/keys/astro-release.jks --ks-key-alias astro \
    --ks-pass pass:"$PW" --key-pass pass:"$PW" \
    --out /workspace/apk-share/astro-1.12.6-${ABI}.apk /tmp/z.apk
done
```

**Symptom -> cause:** "الحزمة غير صالحة" (invalid package) on an otherwise
correct APK is almost always a **truncated download**. If the server is plain
`python -m http.server` it ignores HTTP Range, so an interrupted 130-185 MB
transfer on a mobile network silently saves a partial file that Android cannot
parse. Verify the *downloaded* copy with `apksigner verify`, `zipalign -c 4` and
`sha256sum -c SHA256SUMS.txt`, and confirm the endpoint answers `206` to a
`Range: bytes=0-99` probe, before blaming the build.

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
- **Durable public mirror (preferred):** the GitHub release `astro-v1.12.6` on
  the public repo `hamza2234/ASTROOO12345` hosts the three signed APKs as
  anonymous, permanent, Range-capable assets (verified: `206` to a
  `Range: bytes=0-99` probe and a full download whose sha256 matches):
  `https://github.com/hamza2234/ASTROOO12345/releases/download/astro-v1.12.6/<name>.apk`.
  Prefer these links: they survive the sandbox idle-pause, so a phone never
  saves a 502 "Bad Gateway" body as a truncated `.apk`. The release on the
  **private** `hamza2234/astro-backup` repo still returns 404 for anonymous
  users, so do not share those asset URLs.

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
- **Chip sits in the gutter, never on the icon.** Each reels pill is placed by
  `ReelsSideChip`, which reports a zero-size node and parks the chip to the *left*
  of its rail icon (vertically centred). The side rail is a narrow trailing column
  with no room above or below, so an overlay on the icon would bury it; the empty
  band of video left of the rail is the only clear space. The zero-size node keeps
  the rail slot from widening, so the icon column stays aligned.
- **One-reel snap.** `rememberReelsSnapFlingBehavior` in `ShortsFeedLoaded.kt`
  drives the scroll itself instead of clamping the stock fling: a flick past
  `minimumFlingVelocity` advances exactly one reel from where the drag ended, a
  slow release settles on the nearest reel, and the coast is one bounded spring
  animation. The old `calculateApproachOffset` clamp still let a hard flick coast
  two items and the extra decode made the feed stutter.

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

## Nests: self identity lags on room entry (name/picture "زائر" feel)

Report (Arabic): "when I enter a room sometimes my name and picture show
up, sometimes they arrive late." Nothing renders a literal "guest"
string — the perceived "visitor" is just the placeholder identity that
shows while the local user's row is missing: `UsernameDisplay` falls
back to a truncated `npub…` and `RobohashFallbackAsyncImage` to a
generated robot when the kind-0 metadata (or the presence row itself) is
not yet available.

Root cause: `NestViewModel.connect()` used to wait for our own kind-10312
heartbeat to travel out to a relay and come back through the room
subscription before the local user appeared in `_presences`. Until that
round-trip landed, the user was absent from the participant grid, so
their avatar/name simply popped in seconds later — the intermittent
delay.

Fix: `connect()` now calls `publishLocalPresenceHint()` immediately, so
the local user is placed in the room the instant we start connecting.
The real heartbeat overwrites the optimistic hint when it arrives (the
aggregator keeps the newest `created_at`). `UsernameDisplay`'s
`observeUserInfo` then kicks off the metadata fetch for our own pubkey
right away. The persisted `backupUserMetadata`
(`UserMetadataState.init` → `LocalCache.justConsumeMyOwnEvent`) means the
name/picture are already in cache on a returning user, so the seed makes
them render on the first frame.

## Nests: guest flashes off the stage after a host republish

Follow-up to the open-mic fix above. `buildParticipantGrid` gated every
presence on `presenceIsFresh = pres.updatedAtSec >= roleGrantSec`, where
`roleGrantSec` is the room event's `created_at`. But `HostMicSeatAuthority`
republishes the room (bumping `created_at` to now) on *every* seat
change, so a guest whose last 15-s heartbeat predates that republish was
treated as stale and dropped to the audience until their next heartbeat —
the "guest keeps flashing off the mic" report.

Fix: a live self-claim (`onstage && micSeat != null`) is positive,
peer-authored intent and is now honoured regardless of the role-grant
clock (`liveSelfClaim`). The freshness bypass is scoped to self-claims
only — a peer with no seat claim whose presence predates the role grant
still yields to the role tag (the promoted-audience-member case the gate
was originally added for). Regression tests
`openMicGuestWithSeatClaimOlderThanRoleGrantStillAscends` and
`stalePresenceWithoutSeatClaimStillYieldsToRoleGrant` in
`commons/.../viewmodels/ParticipantGridTest.kt`.

## Nests: room always opens immediately; audio status is a non-blocking banner

`NestActivityContent.kt` mounts the full room (`NestFullScreen`)
unconditionally. It used to gate the whole screen on the MoQ session
having connected at least once (`hasConnected`), showing a full-screen
`RoomConnectingState` until then — but the chat, presence, reactions and
stage all ride the relay subscription (LocalCache), not the audio
transport, so they are ready the moment the kind-30312 resolves. Gating
them on audio meant a room whose audio couldn't come up (UDP/QUIC
blocked by the carrier, a captive portal, a relay that never answers)
never opened at all — the user was pinned on "loading room /
reconnecting" forever. The transport also runs an unbounded reconnect
policy, so it never gave up on its own. Do NOT reintroduce a gate here:
the room must open instantly and stay usable even if audio fails.

The connect status is now `ConnectionOverlay`, a compact banner pinned
to the top of the room. It renders nothing once `ConnectionUiState` is
`Connected`; while `Connecting`/`Reconnecting` it shows the live step +
Leave; on `Failed` it adds a Retry button. It is deliberately NOT a
full-screen scrim — the room underneath is live and must stay
interactive.

## Nests: join banner, larger bottom text

- `stage/JoinBanner.kt` renders a transient "X joined the room" chip under
  the mic grid, driven by `NestViewModel.roomNotices` (ENTERED notices),
  auto-fading after ~3.5 s. String: `nest_join_banner` (EN + AR).
- Bottom-of-room text was too small: the composer text + placeholder are
  now 16 sp (`NestEditFieldRow.kt`) and the error status strip is
  `bodyMedium` (`ActionBarStatusStrip` in `NestActionBar.kt`).

## Branding: display name is Astro, code identity stays amethyst

The product is **Astro**. The app label (`app_name`) already said Astro, but
plenty of user-visible copy still said "Amethyst". Rules:

- **User-visible text → Astro.** Every `res/values*/strings.xml` locale was
  rewritten (`Amethyst` → `Astro`, ~1500 strings across 57 files). Keep new
  strings saying Astro.
- **App-level literals → Astro.** `AppModules.appAgent` (HTTP User-Agent
  `Astro/<version>`), `AccountViewModel` PodcastBoostagram `appName`, the
  AWT tray `appLabel`, the media-saver subfolder (`ASTRO_SUBDIRECTORY =
  "Astro"`), and the app-function messages all say Astro now.
- **The launch/login/sign-up logo is the app icon.** `ic_astro_logo` (a
  cropped copy of `ic_launcher_foreground`, per-dpi PNGs) replaces the
  `CustomHashTagIcons.Amethyst` vector on `LoginScreen`/`SignUpScreen`.
- **Do NOT rename code identity.** Package `com.vitorpamplona.amethyst`,
  the `Amethyst` class, module names (`:amethyst`, …), the applicationId,
  and the NIP-89 `client` tag (`AccountCacheState.CLIENT_TAG_NAME`) stay as
  they are: the client tag is a protocol identifier that other clients match
  on, and a package rename touches every import + the manifest. `Amethyst`
  in comments/Kdoc is fine to leave.

## Notifications: Instagram/Facebook-style activity rows

`ui/note/NotificationActivityRow.kt` renders one activity per row, IG/FB style:
an actor avatar with a small coloured badge stamped bottom-right for the
activity kind (`NotificationActivityType`: LIKE red, COMMENT blue, REPOST green,
ZAP orange, GIFT purple, REACT blue, FOLLOW blue), the actor name in bold inside
the sentence, a 56 dp rounded thumbnail of the target post (with a play glyph for
video), and an unread accent dot. `MultiSetCompose.kt` computes `isUnread` from
the row background vs `colorScheme.background` and passes the matching
`activityType` for comments, boosts, likes/reactions/gifts, and zaps.

The bell lives in the Home top bar via `NotificationsTopBarIcon` in
`navigation/topbars/UserDrawerSearchTopBar.kt`, next to the create (+) and search
actions, and reuses `AddNotifIconIfNeeded` so its unread dot matches the bottom bar.

Note: the notification feed is built from reactions on your own posts
(`MultiSetCard`); there is no "started following you" data source, so the FOLLOW
badge exists for future use but no follow events are produced yet.

## Build environment (container)

The container recycles periodically and wipes `/usr/lib/jvm` and `~/.gradle`.
Keep toolchains under `/workspace` so they survive:

- JDK 21 copied to `/workspace/jdk` (`org.gradle.java.home=/workspace/jdk`).
- `GRADLE_USER_HOME=/workspace/gradle-home` (wrapper + dependency cache).
- Android SDK at `/workspace/android-sdk`.
- Source `/workspace/env.sh` before every Gradle call.

### Repairing the JDK after a container recycle

The copied JDK keeps `/etc/java-21-openjdk/...` and `/usr/share/...` symlinks
that the recycle deletes, leaving `conf/security/java.security` (and friends) as
dangling links. Every JVM then dies with `InternalError: Error loading
java.security file` — including the Gradle wrapper, which needs `MessageDigest`
just to hash its distribution path. Restore the files from the JDK's own jmods:

```bash
python3 - <<'PY'
import zipfile, os, glob
for p in glob.glob('/workspace/jdk/jmods/*.jmod'):
    z = zipfile.ZipFile(p)
    for n in z.namelist():
        if n.endswith('/') or not (n.startswith('conf/') or n.startswith('lib/security/')):
            continue
        out = os.path.join('/workspace/jdk', n)
        if os.path.islink(out) or not os.path.exists(out):
            os.makedirs(os.path.dirname(out), exist_ok=True)
            if os.path.islink(out):
                os.unlink(out)
            open(out, 'wb').write(z.read(n))
PY
```

`java -version` still prints fine with the broken links, so check with
`/workspace/jdk/bin/jmod --version` (or any `MessageDigest` call) instead.

`gradle.properties` pins `-Xmx4g` for the Gradle and Kotlin daemons: 3 GB OOMs on
`:amethyst`, and going much higher risks a container restart. Builds:

- `./gradlew --no-daemon :amethyst:compileFdroidDebugKotlin` to type-check fast.
- `./gradlew --no-daemon :amethyst:assembleFdroidDebug` for the APK
  (`amethyst/build/outputs/apk/fdroid/debug/`).

## Remote-editable gift catalog

Gifts are editable from a web dashboard without shipping a new APK.

- **App side.** `RemoteGiftCatalog` (commons) is the `@Serializable`
  payload; `RoomGiftCatalog.setRemote()` installs it as an override over
  the bundled SKUs, and `effective` is what the panel and overlay read.
  `RemoteGiftStore` (amethyst `nests/room/gifts`) applies the on-disk
  copy first, then refreshes over the network, so a cold start is never
  blocked on a request and a failed fetch keeps the last good catalog.
  The catalog URL is `BuildConfig.GIFT_CATALOG_URL` (blank disables the
  remote layer; the F-Droid build stays hermetic by default).
- **Assets.** A SKU's `svga`/`icon`/`sound` is either a bundled path or
  an `http(s)` URL. `GiftAsset.of()` classifies it and
  `GiftAssetDownloader` fetches it into the app cache keyed by the URL's
  SHA-256, mirroring `RoomFontLoader`. `SvgaGiftPlayer` decodes a remote
  clip from the downloaded file. Relative paths in the payload resolve
  against the catalog's own directory; `javascript:`/`file:` are
  rejected in `RemoteGiftCatalog.safeAsset`.
- **Dashboard.** `tools/gift-dashboard/server.py` (stdlib only; the
  running copy is `/workspace/gift_dashboard`) serves the UI on port
  12001 along with `/gifts/catalog.json` and the content-addressed
  assets under `/gifts/`. Upload a video → it runs
  `/workspace/svga_tools/{video_to_svga,make_gift_sound}.py`
  (background key, crop, square fit, SVGA encode, icon, tier sound) →
  an animated preview appears in the draft list → the operator presses
  **publish** to promote the draft into the live catalog (a draft is
  invisible to the app until then). Assets are named by content hash,
  so a republish is a new URL and no client serves a stale clip.

  ```bash
  cd /workspace/gift_dashboard && /workspace/venv/bin/python server.py --port 12001
  ```

  `api_publish` must look the gift up **among `state["gifts"]` only**:
  `find_gift()` also scans drafts, so using it there matched the draft
  itself, skipped the "add to catalog" branch and deleted the draft —
  publish answered `200` while the gift silently vanished. Regression
  tests: `tools/gift-dashboard/test_server.py` (drives the real handler
  in-process; run with `/workspace/venv/bin/python`).

  `api_publish` must also succeed when there is **no draft** but the gift
  is already live: replacing a clip for a published gift runs the same
  upload-then-publish sequence as a new one, and the second publish used
  to answer `404 unknown draft` even though the upload had landed and the
  catalog had already been refreshed. The worker (`gifts_server.js`) and
  the local server now both treat "no draft, but live" as success.
- **Health check / prune.** `tools/gift-dashboard/check_catalog.py` reads
  the live catalog and state straight from R2 and verifies, per gift,
  that a clip exists, that every referenced asset is in the bucket, that
  the frame size is sane, and that the clip is **not blank** — a
  fully-transparent clip renders nothing while still charging the sender,
  and gift `2585` shipped exactly like that (96 transparent SVGA frames,
  an all-zero alpha video). `--prune [--apply]` drops the broken gifts
  and republishes, copying `state.json`/`catalog.json` to `backups/` in
  the bucket first, so a prune is reversible. It uses the Cloudflare API
  token, not the panel's `ADMIN_TOKEN`, so it works with no worker auth.
- **Instant burst.** A gift must appear the moment it is sent. Two
  separate costs hid behind the delay, and both are now paid up front:
  - *File.* `RoomGiftOverlay` prefetches every remote clip into the app
    cache as soon as the catalog is known (`GiftAssetDownloader`).
  - *Decoded entity.* The SVGA parser caches only the **file**, not the
    decoded `SVGAVideoEntity`, so each send re-parsed a multi-megabyte
    clip while the burst was already on screen. `SvgaEntityCache` (a
    6-entry LRU in `SvgaGiftPlayer.kt`) holds the decoded entity, and
    `warmSvgaEntity()` decodes each catalog clip right after its
    download, so a send only calls `setVideoItem` + `startAnimation`.
  - *Sound.* `GiftSounds.preload()` previously ignored remote sounds
    (bundled only), so the first send paid the download + `prepare()`.
    It now downloads the clip and keeps a prepared `MediaPlayer` per
    URL, replayed with `seekTo(0)`.
  - The dashboard exposes the built APK at `/download/apk` (newest
    universal build under `/workspace/astro-backup`), with a download
    button on the UI, so the operator can install the current app
    without a manual `adb` push.

- **Placement from the dashboard.** A SKU also carries `scale`, `posX`
  and `posY`. `scale` (0.4–1.2) sizes the clip and `posX`/`posY`
  (−0.5..0.5, a fraction of the screen) move its centre off dead-centre,
  so an operator can seat a takeover over the room without re-encoding.
  The dashboard's preview modal renders the clip on a phone-shaped,
  checkerboard stage with sliders for all three; the same values are
  editable inline in the gift table. The app maps them in
  `RemoteGiftCatalog.toSkus` (clamped) onto `RoomGiftSku.offsetX/offsetY`,
  which `RoomGiftOverlay` applies as a `graphicsLayer` scale + translate
  (the box reports its pixel size via `onSizeChanged`). Absent fields
  mean `scale = 1.0` and centred.
- **Previews.** `clip_preview(gift)` looks up a per-gift WebM (VP9 alpha,
  so the browser shows the real transparency) plus an opaque MP4
  fallback and the icon as poster; `build_preview_from_clip(gift)`
  rebuilds any that are missing from the stored SVGA at startup
  (`video_to_svga.py --passthrough`), and `send_file` honours HTTP
  Range so the player can seek.
- **Text/watermark removal.** `video_to_svga.py` strips burnt-in
  captions and watermarks by default: `remove_static_overlays()` flags
  pixels that barely change across the clip, keeps only whole connected
  components that are actually still, and erases the rest — the moving
  subject survives. `--no-strip-text` keeps them.
- **Edge fade.** A keyed source video usually keeps an opaque rectangle
  right to its content edge, which the app draws as a hard-edged "screen"
  floating over the room. `feather_border()` multiplies the alpha by a
  smooth ramp that reaches zero at the content's own bounding box, so the
  clip dissolves into the room instead. It runs by default on a normal
  encode (`--edge-fade` sets the ramp width, `--no-edge-fade` disables
  it). The ramp only scales alpha down, so a clip that already fades out
  on its own (a soft glow) is left visually intact — the fade is never
  doubled in a way that matters. `--passthrough` (which reproduces a
  stored clip, e.g. to rebuild a preview) leaves the fade off unless
  `--edge-fade` is passed, so a preview always matches what the app plays.
- **Borderless blend (opaque clips).** A clip uploaded without background
  removal (`--bg none`) is an opaque rectangle, and a border ramp only
  softens it — the room still shows a rectangular glow around the video.
  `blend_into_room()` instead feathers the alpha along a rounded-rectangle
  signed-distance field, so the clip reaches zero alpha on its own outline
  with rounded corners and no straight edge remains to read as a "screen".
  It applies only when `_is_opaque_rectangle()` says the clip is an opaque
  block (the bbox outline is mostly opaque); a keyed cutout's alpha is its
  silhouette, so it keeps its shape and gets `feather_border()` instead.
  `--blend-feather`/`--blend-corner` tune it, `--no-blend` falls back to
  the plain ramp. The dashboard's "دمج الحواف في الغرفة" select maps
  auto/strong/soft/off onto these flags, so the operator never picks a
  numeric width.
- **Decode resolution cap.** `extract_frames()` scales the source down so
  its long side is at most `--decode-max` (default 720) before handing the
  frames to numpy. A 1080p clip decodes to ~8 MB per RGBA frame, so 96
  frames at source resolution peaked at ~4.6 GB and risked an OOM kill on
  a large upload; capping the decode drops that to ~0.6 GB with no visible
  loss (the clip is resized to `--size` at the end anyway). Set
  `--decode-max 0` to decode at full resolution.
- **"Off" must mean off.** `resolve_edge_fade()` is the single place that
  turns the blend choices into fade flags, used by both the normal and
  `--passthrough` paths. `--no-blend` used to still leave a 10% fade, so a
  clip the operator asked to leave untouched arrived with a faint edge.
  It now yields alpha 255 at the border, and the dashboard defaults the
  "دمج الحواف" select to `off` for the same reason.
- **Aspect, not a forced square.** `--fit aspect` (dashboard: "كامل بنسبة
  الفيديو" + "لا تقصّ") keeps the source's real shape; the square default
  squashed a vertical clip into 480×480, which read as "the video is
  squeezed top and bottom". The preview stage uses `object-fit: contain`
  on the same phone-shaped box as the room so it matches the app.
- **Serve every work host.** The dashboard listens on 12001 *and* 12000
  (`--also-port 12000`) because a work host with no listener answers the
  proxy with `502`, which looks exactly like a dead button: the page never
  loads and no request ever reaches the server. `STATE_LOCK` +
  write-then-rename make the two listeners safe against concurrent
  uploads, and a port that fails to bind is logged, not fatal.
- **A "dead button" is usually a hidden message.** `#go`'s guard messages
  render *below* a long form, so an operator who clicked without choosing
  a file saw nothing happen. `setStatus()` now scrolls the message into
  view, the button opens the file picker when no file is chosen, the
  chosen filename is echoed back, and the upload handler is wrapped in
  `try/catch` so a missing field reports itself instead of failing
  silently.
- **Client aborts are not errors.** A `<video>` with a WebM and an MP4
  `<source>` cancels the loser, and the abort reached `send_file` as
  `ConnectionResetError`/`BrokenPipeError` → a 500 and a traceback per
  preview. `_write_body()` swallows `ConnectionError`. The page's
  `window.onerror` handler also ignores resource-load failures
  (`e.target.tagName` set), so a cancelled media fetch is never reported
  as a page error.
- **Read the clip's own stream, not its cover.** A phone video can carry an
  attached cover image (a `mjpeg`/"attached pic" stream, usually 16:9).
  `probe()` walked every `Video:` line and kept the last, and `ffmpeg`
  selected the highest-resolution stream by default, so a 720×960 vertical
  clip was decoded and reported as 1280×720 → the whole scene was squashed
  into a landscape box and looked "bordered". `probe()` now stops at the
  first real video stream and skips `mjpeg`/`attached pic`, and
  `extract_frames` passes `-map 0:v:0`. The fps token also carries a
  trailing comma (`30 fps,`), which is why it used to parse as `None`.
- **`fit cover` = fill the room.** The operator wants a takeover that
  reaches every edge of the room screen, cropping the overflow, with the
  removed background letting the room show through — not a picture floating
  with a transparent margin. `fit_cover()` scales to *cover* (max ratio,
  centred crop) into a box shaped like the room (`cover_size()`,
  `--room-aspect`, default 9:16), so no letterbox remains. `fit aspect`
  (letterbox, keeps the source shape) and `fit square` are unchanged. The
  dashboard exposes it as **ملء الغرفة بالكامل**; `shape_args_for()` passes
  `--room-aspect` only in this mode. The app draws the clip with
  `FIT_CENTER` over a `fillMaxSize` box, so the room's 9:16 shape is what a
  cover clip must match.

## Alpha-video gift clips (cinematic gifts, hardware-decoded)

An SVGA clip is a zip of PNG frames: multi-megabyte, decoded on the CPU, and
re-parsed on a cache miss. The *same* clip packs into an alpha video that is
roughly a tenth the size and decodes in hardware. The app prefers the video
and keeps SVGA as the fallback for gifts authored before the packer existed.

- **Packer.** `tools/gift-dashboard/alpha_pack.py` reads an `.svga` and
  writes an `.mp4`:

  ```sh
  python3 alpha_pack.py clips/<hash>.svga -o videos/<hash>.mp4
  python3 alpha_pack.py --batch <in-dir> --out-dir <out-dir>   # whole catalog
  ```

  It stacks the RGB frame over a grayscale copy of its alpha into one image
  (the video is twice the frame height), so a single H.264/VP9 stream carries
  both planes and any player can decode it. `--crf`/`--preset` tune the
  encode, `--json` prints a per-clip report. The alpha strip is three
  identical channels, so the shader samples green — 4:2:0 chroma subsampling
  cannot bleed into the matte. A source whose every pixel is transparent is
  rejected (`fully transparent (nothing to pack)`) rather than packed into an
  all-black video that would draw *nothing*; in `--batch` that clip is listed
  under `skipped` in `manifest.json` and the rest still pack.
- **Blank-clip guard in the authoring tool.** `video_to_svga.py` refuses to
  write a clip whose subject coverage is below `--min-coverage` (default
  0.5%). The guard originally ran only on the keyed path (`mode != "none"`),
  so a source that already carried alpha — where no keying happens — skipped
  it entirely; gift `2585` was authored through that hole and shipped 96
  fully-transparent frames. The check now applies to every path, including
  `--passthrough`. Regression tests: `tools/gift-dashboard/test_video_to_svga.py`.
  The end-to-end check that catches a blank gift already live is
  `check_catalog.py` (see the dashboard section).
- **Playback.** `AlphaVideoGiftPlayer` wraps a `GLSurfaceView`;
  `AlphaVideoRenderer` binds the decoder to an `GL_TEXTURE_EXTERNAL_OES`
  `SurfaceTexture` and samples it with `samplerExternalOES`. The fragment
  shader maps each output pixel into its own half of the packed image
  (colour from the top half, alpha from the bottom), and both samples go
  through `SurfaceTexture.getTransformMatrix`, which is what actually puts
  the image upright on every device — never a hand-rolled V flip. The quad is
  letterboxed to the clip's `videoWidth`/`videoHeight` so a square or vertical
  clip keeps its shape instead of stretching to the screen.
- **Catalog.** A remote SKU may carry `video` (a URL/path) plus
  `videoWidth`/`videoHeight`; `RemoteGiftCatalog.toSkus` maps them onto
  `RoomGiftSku.video/videoWidth/videoHeight`. `RoomGiftOverlay` uses the
  video only when both dimensions are present, so a half-authored row falls
  back to `svga`. Both clip kinds drive the same ready/finished deferreds, so
  the burst's hold clock is identical either way. `videoWidth`/`videoHeight`
  are always the *single-frame* size: the browser panel halves the packed
  file's height (`Math.round(h / 2)`) before sending it, matching the local
  tool's `read_alpha_meta`, so the client never has to guess the split.
- **Worker + panel.** `POST /api/gifts/asset` with `kind=videos` stores the
  file under `assets/videos/<sha>.mp4` and records the `width`/`height`
  fields on the draft; `GET /gifts/videos/<name>` serves it as `video/mp4`.
  The panel's **الهدايا** tab has an upload card (`gu-file`/`gu-go`/`gu-id`,
  wired by `wireGiftUpload` in `gifts_ui.js`) that posts straight to that
  route with the page's `authorization: Bearer` header — no `content-type`
  on the multipart body, so the browser sets the boundary itself.

## Room gifts served from Cloudflare (permanent hosting)

The local dashboard (`gift_dashboard/server.py`) is the *authoring* tool: it
runs ffmpeg to turn a phone video into an SVGA clip with the background
removed. It is **not** the delivery path — the sandbox that runs it is
recycled, and when it dies the app's catalog fetch 404s.

Delivery lives in the Cloudflare worker `astro-admin`, in the same
`hmzzhproxy29127.workers.dev` zone as the rest of the backend:

- `GET /gifts/catalog.json` — the published catalog the app fetches.
- `GET /gifts/clips|videos|icons|sounds/<name>` — asset bytes from the R2
  bucket `astro-gifts` (`assets/<kind>/<name>`), immutable and long-cached.
  `videos/` holds the alpha-packed H.264 the app actually plays. These
  routes honour a single byte range (`Accept-Ranges: bytes`, `206` +
  `Content-Range`), and the R2 slice is requested explicitly because the
  R2 object API ignores a `Range` header — so a clip can seek and a big
  download can resume.
- `GET /gifts/app.apk` — the latest build, stored as `app.apk` in the
  bucket. It also serves ranges (a 190 MB download resumes) and is
  `no-cache` rather than immutable, because the file is replaced in place
  on each build.
- `GET /api/gifts/state`, `POST /api/gifts/{gift,delete,publish,discard,asset,meta,import}`
  — the operator API behind the panel's **الهدايا** tab. `publish` accepts
  a gift whose only clip is an alpha video (no `svga` required), and it is
  idempotent for an already-live gift (see the publish note above).

The worker module has a regression suite that needs no Worker runtime:
`tools/gift-dashboard/test_worker.mjs` evaluates `gifts_server.js` in a VM
with the Web globals the runtime provides and drives the handlers against a
fake R2 bucket. Run it with `node tools/gift-dashboard/test_worker.mjs`; it
pins catalog shape, video-only publish, re-publish, asset upload/serve,
ranges, the traversal guard and delete.

The app's catalog URL is `BuildConfig.GIFT_CATALOG_URL`, defaulted in
`amethyst/build.gradle.kts` to
`https://astro-admin.hmzzhproxy29127.workers.dev/gifts/catalog.json` and
overridable with `-PgiftCatalogUrl=...`. Changing it needs a new APK; the
in-app value is only ever read (`RoomGiftOverlay.kt`).

Publishing a newly authored gift from the local box:

```sh
cd /workspace/gift_dashboard
python3 publish.py push <gift-id>   # one gift (assets + catalog row)
python3 publish.py push-all         # resync every published gift
```

The publishing scripts live in `/workspace/cf_gifts/` and are mirrored
into the repo under `tools/gift-dashboard/cloudflare/` (plus `alpha_pack.py`
and `video_to_svga.py` beside `server.py`) so the whole pipeline is versioned
rather than living only on the sandbox:

- `gifts_server.js` / `gifts_ui.js` — the module injected into the worker.
- `build_admin.py` — patches the **pristine** original bundle (kept in
  `pristine/index.js`) and deploys. It re-declares every plain_text and d1
  binding on upload, because a `PUT` does not preserve them; `secret_text`
  bindings are omitted, and Cloudflare keeps their stored value (verified).

The injection is additive: the original bundle's lines are never rewritten,
only new routes/nav/UI inserted at unique anchors. `node --check` on the
patched bundle plus a diff that shows zero removed lines are the two guards
worth re-running before a deploy.

The panel's **الهدايا** tab has a live preview player (`gifts_ui.js`,
`openGiftPreview`). A gift's `videos/` asset is *alpha-packed* — the packer
stacks the colour frame over an alpha strip, two frames tall — so a plain
`<video>` shows a doubled image. The preview unpacks it on a WebGL canvas the
same way the app's renderer does: RGB from the top half, alpha from the green
channel of the bottom half (`AlphaVideoRenderer.kt`), premultiplied and drawn
over a mock of the room. It mirrors the app's `contain` / `fill` modes (fill =
a vertical clip covers 95% of the phone height), plus scrub, frame-step, speed,
loop, in/out points, scale, position, alpha and a grid. A **حفظ** button writes
scale/posX/posY back through `POST /api/gifts/gift`, which is how a gift is
placed without shipping a new APK.

Both upload paths show a progress bar, because a 20–190 MB alpha video over a
phone connection gives no feedback otherwise. The panel's **رفع** posts the
multipart body through `XMLHttpRequest` (`uploadWithProgress`) rather than
`fetch`, which cannot report upload progress, and drives a gradient fill plus a
percentage; the local dashboard's `#prog` bar fills during upload and then
switches to an indeterminate sweep while the server transcodes, since that
phase streams no progress back.

Authoring a matte (video → alpha) with BiRefNet peaks near 7 GiB of RAM, which
on a shared box is enough to OOM-kill a run and the session with it. Two guards
keep that from happening: the ONNX session is built with a small thread count,
no CPU arena and no memory pattern (`_birefnet_session`), and
`birefnet_affordable` refuses a run when free RAM minus `BIREFNET_PEAK_MB` is
below a headroom margin — `--matte auto` then falls back to the colour key and
`--matte birefnet` fails with a clear message rather than crashing. The rembg
model cache is pinned to `/workspace/.rembg` (`REMBG_HOME`) because `/tmp` and
`$HOME` are wiped on every sandbox restart.

The pre-room screens were redesigned from a grid of full-width 16:9 cover
cards into a compact list (`NestsFeedLoaded.kt`: `nestsRoomList` / `NestRoomRow`,
an 84dp row with a 68dp thumbnail) and the "my room" hero is now a slim row
(`NestsLobbyChrome.kt`). The room chrome gained a gradient header/footer and
gradient mic rings (`MicSeatsGrid.kt`: `micSeatRing`).

