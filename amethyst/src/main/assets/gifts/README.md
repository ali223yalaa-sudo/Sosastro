# Bundled gift animations (SVGA)

These `.svga` clips back the room gift catalog in
`commons/.../viewmodels/RoomGiftsState.kt`. Every gift in the catalog
has its own clip and no two gifts share one, so each gift plays its
own cinematic.

| File(s) | Source | License |
| --- | --- | --- |
| `car.svga`, `cat.svga`, `sports_car.svga`, `angel.svga`, `pin_jump.svga` | Tencent [TUILiveKit](https://github.com/Tencent-RTC/TUILiveKit) `Flutter/live_uikit_gift/assets/svga/` | MIT |
| `rose.svga`, `rose_v2.svga`, `castle.svga`, `rocket.svga`, `christmas.svga`, `heartbeat.svga` | [svga/SVGAPlayer-Android](https://github.com/svga/SVGAPlayer-Android) `app/src/main/assets/` sample assets | Apache-2.0 |
| `gen_*.svga` | Authored for this project (see below) | MIT |
| `icons/*.png` | Rendered from [Twemoji](https://github.com/jdecked/twemoji) vector art | CC-BY 4.0 |

The `gen_<gift>.svga` clips are authored per gift: each carries its
own accent palette, particle motif (petals, sparks, hearts, fireworks,
…) and choreography, so no two gifts share a cinematic. Their sprite
art uses the matching Twemoji vector for the gift plus procedurally
generated glow/ring/ray/particle layers. They are ordinary SVGA v2
clips (a ZIP holding `movie.spec` plus one PNG per image key) and play
through the same Apache-2.0 `SVGAPlayer-Android` runtime as the
imported ones.

`icons/<gift>.png` are the real per-gift icons the panel draws, one
per catalog entry, rendered from Twemoji.

Both upstream projects ship the corresponding LICENSE files in their
repositories. The SVGAPlayer-Android runtime used to play these clips
(`com.github.yyued:SVGAPlayer-Android`) is likewise Apache-2.0.
