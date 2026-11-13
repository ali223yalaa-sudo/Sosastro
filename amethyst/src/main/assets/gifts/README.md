# Bundled gift animations (SVGA)

These `.svga` clips back the room gift catalog in
`commons/.../viewmodels/RoomGiftsState.kt`. They are imported verbatim
from permissively licensed open-source gift packs; the app itself does
not author or modify them.

| File(s) | Source | License |
| --- | --- | --- |
| `car.svga`, `cat.svga`, `sports_car.svga`, `angel.svga`, `pin_jump.svga` | Tencent [TUILiveKit](https://github.com/Tencent-RTC/TUILiveKit) `Flutter/live_uikit_gift/assets/svga/` | MIT |
| `rose.svga`, `rose_v2.svga`, `castle.svga`, `rocket.svga`, `christmas.svga`, `heartbeat.svga` | [svga/SVGAPlayer-Android](https://github.com/svga/SVGAPlayer-Android) `app/src/main/assets/` sample assets | Apache-2.0 |

Both upstream projects ship the corresponding LICENSE files in their
repositories. The SVGAPlayer-Android runtime used to play these clips
(`com.github.yyued:SVGAPlayer-Android`) is likewise Apache-2.0.
