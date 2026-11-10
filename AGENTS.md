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
- Android SDK at `/home/openhands/android-sdk` (set `ANDROID_HOME`).
  `compileSdk`/`targetSdk` = 37, `minSdk` = 26 (see `gradle/libs.versions.toml`).
- Gradle wrapper downloads Gradle 9.4.1 on first run.

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/openhands/android-sdk
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

