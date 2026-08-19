---
title: Astro Reels Gifts, Location, Chrome & Caption UX
type: feat
status: active
date: 2026-08-18
---

# Astro — Reels Gifts, Location, Chrome & Caption UX

Batch of Instagram-style parity fixes for the Astro Android fork: reels interactions,
video publish captions, home chrome stability, profile gifts showcase, Around Me
location labels, and description UX (expand / translate / TTS).

## A. Reels — gifts & likes

### Current
- `ZapReaction` (star → `GiftSupportSheet`) appears in post `ReactionsRow` only.
- `ShortsReelsSideReactions` has like / reply / boost / share / bookmark / more — **no gift**.
- Double-tap in `VideoCardCompose` uses `reactionChoices().firstOrNull()` (often 🚀), not `"+"`.
- Long-press on heart already opens `ReactionChoicePopup` via `LikeReaction`.

### Target
| Action | Behaviour |
|--------|-----------|
| Side stack | Add `ZapReaction` (white tint, 30dp) between like and reply |
| Double-tap | Always react with `"+"`; show heart overlay (unchanged) |
| Long-press heart | Sticker popup — already matches posts |

### Files
- `ReactionsRow.kt` — `ShortsReelsSideReactions`
- `VideoCardCompose.kt` — `onDoubleTapAction`

### Acceptance
- Gift star visible on every active reel page; tap opens gift sheet.
- Double-tap never sends first sticker emoji; sends default like `"+"`.

---

## B. Video upload — URL in description

### Root cause
`HlsKind1SiblingBuilder.build()` joins `title`, `description`, **`masterUrl`** into kind:1
`content`. URL is already in `imeta` + `r` tags → visible as caption after publish.

### Fix
Content = `title` + `description` only (trimmed, `\n\n` joined). URL stays in tags.

### Files
- `HlsKind1SiblingBuilder.kt`
- `HlsKind1SiblingBuilderTest.kt`

### Acceptance
- New HLS publishes show title/description as caption; no raw m3u8/mp4 URL line.

---

## C. Description UX (posts, profile, reels)

### Posts & profile about
Already use `TranslatableRichTextViewer` → `ExpandableRichTextViewer` (show more + translate).

### Gaps
1. **Profile about** — add TTS icon (`DrawPlayName`) inline, same pattern as display name.
2. **Reels caption** — plain `Text`; strip media URLs; add translate wrapper + TTS icon;
   keep 2-line expand / show more (white overlay styling).

### Files
- `DrawAdditionalInfo.kt` — ABOUT block
- `VideoCardCompose.kt` — `ShortsReelsExpandableCaption`

### Acceptance
- Long profile bios: show more + translate (existing) + read-aloud icon.
- Reels caption: no video URL text; translate + TTS without breaking overlay layout.

---

## D. Home top bar — empty gap at first post

### Root cause
`DisappearingScaffold` publishes **static** `contentPadding.top = topHeight` while the bar
moves via `translationY = topHeightOffset`. When user returns to item 0 with bar partially
hidden, content keeps full top inset → blank band where bar/stories should be.

Secondary: `WatchRevealChromeAtFeedTop` sets `revealUnlocked = true` at top but never
`false` when scrolling away.

### Fix
1. Dynamic padding: `top = (topHeight + topHeightOffset).coerceAtLeast(0)`.
2. When leaving top: `revealUnlocked = false`.

### Files
- `DisappearingScaffold.kt`
- `VideoScreen.kt` — `WatchRevealChromeAtFeedTop`

### Acceptance
- Scroll to first post → top bar + stories appear immediately, no empty strip.
- Mid-feed reverse scroll does not reveal chrome until back at first item.

---

## E. Profile — gifts board (replace Zap tab)

### Current
`ProfileTab.Zaps` → `TabReceivedZaps` (Lightning zap list). Tab label uses `R.string.zaps`.

### Target
- Tab label: **هدية** / **Gifts** (not زاب).
- Content: scrollable **card grid** of gifts **received by this profile** only.
- Layout: **3 gifts per row** — emoji + count below each; scroll when many.
- Data: economy Worker `star_gifts` aggregated by `to_pubkey` + `sku_id`.

### API (new)
`GET /v1/stars/gifts/profile/{pubkey64}` → `{ total, bySku: { skuId: count } }`

### Files
- `economy-server/src/starsOps.ts` — `profileGiftSummary`
- `economy-server/src/index.ts` — route
- `StarsApiClient.kt` — `fetchProfileGifts`
- `StarsWalletState.kt` — `profileGifts`
- New `TabProfileGifts.kt`, `GiftTabHeader.kt`
- `ProfileScreen.kt` — wire tab

### Acceptance
- Profile gifts tab shows emoji grid with counts; empty state when none.
- Tab reads "هدية" in Arabic UI.

---

## F. Location — Around Me label

### Current
`FeedFilterSpinner` shows `(Iraq · region)` when `scopeLabel == "region"` — hardcoded English
"region". Server returns `scope: "region"` but not a human label; `loc_region` often unset
because client never reverse-geocodes on GPS push.

### Fix
1. `LocationProfileState`: expose `region` + `geohash` from API responses.
2. On GPS push: send reverse-geocoded city as `region` when available (`CachedReversedGeoLocations`).
3. `FeedFilterSpinner`: show `LoadCityName(geohash)` or stored `region`, else country only —
   never the literal string `"region"`.

### Files
- `LocationProfileState.kt`
- `FeedFilterSpinner.kt`

### Acceptance
- Around Me subtitle shows e.g. `(Iraq · Baghdad)` or city from geohash, not `(Iraq · region)`.

---

## Implementation order

1. Spec (this doc)
2. Quick wins: reels gift + double-tap, HLS content fix
3. Home chrome: dynamic padding + reveal lock
4. Profile gifts: server endpoint + tab UI
5. Location labels
6. Caption / profile TTS polish
7. `./gradlew :amethyst:installPlayDebug`

## Out of scope

- Deploying economy Worker (client/server code kept in sync; existing hosted worker may lag).
- Desktop / iOS parity for reels overlay.
