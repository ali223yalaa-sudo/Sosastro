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

## Sounds

Each gift also plays its own sound effect. The clips live in
`amethyst/src/main/res/raw/gift_<gift>.wav` (mono, 22.05 kHz, 16-bit)
and are named after the gift, so the catalog's `sound = "gift_<id>"`
field resolves straight to a `res/raw` id. No two gifts share a clip.
They are authored by `/workspace/svga_tools/generate_sounds.py`: a
seeded synth picks a consonant pentatonic pitch cluster plus a
tier-scaled flourish (blip / sparkle arpeggio / rising sweep / big
hit) and a motif-specific texture, so a cheap gift reads as a small
chime and a legendary one as a big multi-layer hit. `GiftSounds`
(`.../nests/room/gifts/GiftAssets.kt`) plays them through a
`SoundPool` once per burst.

Both upstream projects ship the corresponding LICENSE files in their
repositories. The SVGAPlayer-Android runtime used to play these clips
(`com.github.yyued:SVGAPlayer-Android`) is likewise Apache-2.0.
