/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.amethyst.commons.viewmodels

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent

/**
 * One gift SKU offered by the room gift panel. Gifts are a *local*
 * catalog: unlike the star wallet's server-priced [GiftSku] there is
 * no billing backend behind a room gift, so the catalog is a fixed
 * constant shipped with the app. That keeps the feature self-
 * contained (no HTTP, no NIP-98 signing, no wallet) while still
 * giving the panel the tiers, emoji and localized labels TRTC's
 * `GiftListView` renders.
 */
@Immutable
data class RoomGiftSku(
    /** Stable id written into the gift tag; never localized. */
    val id: String,
    val emoji: String,
    val labelAr: String,
    val labelEn: String,
    /** 1 = everyday, 2 = premium, 3 = luxe, 4 = legendary. Drives the panel tab. */
    val tier: Int,
    /**
     * Display value, mirroring the "star price" the imported catalog
     * shows next to each gift. Presentation only — a room gift is
     * published as a kind-7 on the user's own relays, so nothing is
     * debited here.
     */
    val value: Int,
    /**
     * Bundled SVGA animation asset (path under `assets/`). Every gift
     * has one and no two gifts share a clip, so each gift plays its
     * own cinematic. The 11 clips imported verbatim from Tencent's
     * MIT-licensed TUILiveKit gift pack and the Apache-2.0
     * `svga/SVGAPlayer-Android` samples are kept for the gifts they
     * depict; the rest are authored per-gift (see `assets/gifts/README.md`).
     */
    val svga: String? = null,
    /**
     * Bundled PNG icon (path under `assets/`), rendered from the real
     * upstream Twemoji vector set. The panel draws this instead of the
     * raw emoji glyph so every gift has a crisp, real icon.
     */
    val icon: String? = null,
    /**
     * Bundled sound effect (a `res/raw` name without extension, e.g.
     * `gift_rose`). Played once when the gift's burst starts. Each gift
     * has its own clip and no two share one.
     */
    val sound: String? = null,
) {
    fun label(arabic: Boolean): String = if (arabic) labelAr else labelEn
}

/**
 * Particle motif drawn behind a gift's emblem when the gift has no
 * bundled SVGA clip. Each gift resolves to exactly one motif (via
 * [RoomGiftSku.theme]), so two different gifts never share the same
 * backdrop — the old fixed "rays + confetti" layer made every gift
 * look identical, which is precisely what it should not do.
 */
enum class GiftMotif {
    Petals,
    Sparks,
    Stars,
    Confetti,
    Bubbles,
    Rings,
    Shockwave,
    Hearts,
    Feathers,
    Fireworks,
    Snow,
    Leaves,
}

/**
 * Per-gift visual identity: the accent pair drives the backdrop tint,
 * rays and emblem chrome, [motif] selects the particle layer, and
 * [variant] shifts the particle count/spread so two gifts that share a
 * motif still differ. Deriving it from the SKU (rather than hard-coding
 * one look for the whole catalog) is what gives each gift its own
 * treatment.
 */
@Immutable
data class RoomGiftTheme(
    val accent: Long,
    val accentDeep: Long,
    val motif: GiftMotif,
    /** Signed ray spin over the burst; sign flips per gift for variety. */
    val rayRotation: Float,
    /** 0..THEME_VARIANTS-1; scales the particle layer. */
    val variant: Int,
    /**
     * Per-gift layout phase. Mixed into every particle seed so two
     * gifts sharing a motif *and* variant still scatter differently —
     * colour alone is not a distinct animation.
     */
    val seed: Int,
)

/** Accent/deep pairs, spread across the hue wheel. */
private val GiftAccents: List<Pair<Long, Long>> =
    listOf(
        0xFFFFD54FL to 0xFFFFA726L, // gold
        0xFFF48FB1L to 0xFFC2185BL, // rose
        0xFF80DEEAL to 0xFF0097A7L, // cyan
        0xFFB39DDBL to 0xFF5E35B1L, // violet
        0xFFFF8A65L to 0xFFD84315L, // coral
        0xFFA5D6A7L to 0xFF2E7D32L, // jade
        0xFFEF9A9AL to 0xFFC62828L, // crimson
        0xFF90CAF9L to 0xFF1565C0L, // azure
        0xFFFFF176L to 0xFFF9A825L, // lemon
        0xFFCE93D8L to 0xFF6A1B9AL, // orchid
        0xFF80CBC4L to 0xFF00695CL, // teal
        0xFFFFAB91L to 0xFFE64A19L, // peach
    )

/** Motifs that read as "the right thing" for a given gift id. */
private val SemanticMotifs: Map<String, GiftMotif> =
    mapOf(
        "rose" to GiftMotif.Petals,
        "bouquet" to GiftMotif.Petals,
        "heart" to GiftMotif.Hearts,
        "kiss" to GiftMotif.Hearts,
        "star" to GiftMotif.Stars,
        "trophy" to GiftMotif.Stars,
        "crown" to GiftMotif.Stars,
        "crownjewel" to GiftMotif.Stars,
        "crownmega" to GiftMotif.Stars,
        "unicorn" to GiftMotif.Stars,
        "gift" to GiftMotif.Confetti,
        "cake" to GiftMotif.Confetti,
        "balloon" to GiftMotif.Confetti,
        "clap" to GiftMotif.Confetti,
        "beer" to GiftMotif.Bubbles,
        "coffee" to GiftMotif.Sparks,
        "perfume" to GiftMotif.Bubbles,
        "icecream" to GiftMotif.Snow,
        "christmas" to GiftMotif.Snow,
        "castle" to GiftMotif.Fireworks,
        "planet" to GiftMotif.Rings,
        "galaxy" to GiftMotif.Rings,
        "galaxyrocket" to GiftMotif.Fireworks,
        "rocket" to GiftMotif.Sparks,
        "jet" to GiftMotif.Sparks,
        "helicopter" to GiftMotif.Feathers,
        "yacht" to GiftMotif.Bubbles,
        "dragon" to GiftMotif.Fireworks,
        "lion" to GiftMotif.Sparks,
        "cat" to GiftMotif.Hearts,
        "angel" to GiftMotif.Feathers,
        "guitar" to GiftMotif.Leaves,
        "teddy" to GiftMotif.Hearts,
        "ring" to GiftMotif.Shockwave,
        "diamond" to GiftMotif.Shockwave,
        "phone" to GiftMotif.Rings,
        "car" to GiftMotif.Sparks,
        "sportscar" to GiftMotif.Sparks,
        "pin" to GiftMotif.Fireworks,
    )

private val AllMotifs: List<GiftMotif> = GiftMotif.entries.toList()

/** How many particle-density variants a motif offers. */
const val THEME_VARIANTS: Int = 3

/** FNV-1a — stable across platforms so a gift always themes identically. */
private fun stableHash(s: String): Int {
    var h = 0x811C9DC5L
    for (c in s) {
        h = h xor (c.code.toLong() and 0xFF)
        h = (h * 0x01000193L) and 0xFFFFFFFFL
    }
    return (h and 0x7FFFFFFFL).toInt()
}

/**
 * Deterministic theme for a sku id. Accent, motif and variant are all
 * derived from [id] via [stableHash], so the panel and the overlay
 * agree without shared mutable state, and an unknown/future SKU still
 * gets a distinct, stable treatment instead of a shared fallback.
 */
private fun themeFor(id: String): RoomGiftTheme {
    val h = stableHash(id)
    val motif = SemanticMotifs[id] ?: AllMotifs[h % AllMotifs.size]
    val (accent, deep) = GiftAccents[(h / 7) % GiftAccents.size]
    return RoomGiftTheme(
        accent = accent,
        accentDeep = deep,
        motif = motif,
        rayRotation = if (h and 1 == 0) 110f else -110f,
        variant = (h / 13) % THEME_VARIANTS,
        seed = h,
    )
}

/**
 * Resolve this gift's [RoomGiftTheme]. Pure and deterministic, so the
 * panel and the overlay agree on the look.
 */
fun RoomGiftSku.theme(): RoomGiftTheme = CatalogThemes[id] ?: themeFor(id)

/**
 * Theme for a raw sku id, tolerating an unknown/future SKU (the
 * catalog keeps such gifts so their emoji still renders). Unknown ids
 * hash into the same accent/motif space as the known catalog, so a
 * gift from a newer peer still gets a distinct, stable treatment
 * instead of the old one-size-fits-all backdrop.
 */
fun giftThemeFor(skuId: String): RoomGiftTheme = CatalogThemes[skuId] ?: themeFor(skuId)

/**
 * Themes for the known catalog, assigned so no two gifts share the
 * same accent *and* motif — the coarse signature a viewer actually
 * perceives. Each gift first tries the accent its hash suggests; if a
 * same-motif gift already claimed it, it takes the next free accent.
 * The catalog's largest motif group is smaller than the accent count,
 * so a free accent always exists and the guarantee holds. Lazy so the
 * catalog can be declared below without an initialisation cycle.
 */
private val CatalogThemes: Map<String, RoomGiftTheme> by lazy {
    val usedByMotif = HashMap<GiftMotif, MutableSet<Int>>()
    val out = LinkedHashMap<String, RoomGiftTheme>(RoomGiftCatalog.all.size)
    for (sku in RoomGiftCatalog.all) {
        val h = stableHash(sku.id)
        val motif = SemanticMotifs[sku.id] ?: AllMotifs[h % AllMotifs.size]
        val used = usedByMotif.getOrPut(motif) { mutableSetOf() }
        val preferred = (h / 7) % GiftAccents.size
        val accentIndex =
            if (preferred !in used) {
                preferred
            } else {
                (0 until GiftAccents.size).firstOrNull { it !in used } ?: preferred
            }
        used.add(accentIndex)
        val (accent, deep) = GiftAccents[accentIndex]
        out[sku.id] =
            RoomGiftTheme(
                accent = accent,
                accentDeep = deep,
                motif = motif,
                rayRotation = if (h and 1 == 0) 110f else -110f,
                variant = (h / 13) % THEME_VARIANTS,
                seed = h,
            )
    }
    out
}

/**
 * The built-in room gift catalog. Ordered so the panel's cheapest
 * tier reads first. Kept in `commons` (not the Android UI) so the
 * send path, the panel and the overlay all resolve the same SKU by
 * id, and so a unit test can assert the catalog is well-formed.
 *
 * Tiers set the *scale* of the treatment, while each gift's
 * [RoomGiftSku.theme] sets its *identity* (accent colour + particle
 * motif), so no two gifts in a tier share a look:
 *   * 1 standard — a small rise.
 *   * 2 premium  — a bigger rise with a sparkle ring.
 *   * 3 luxe     — a full-screen cinematic takeover.
 *   * 4 legendary — a mega takeover (shock rings, screen flash,
 *     giant emblem, sender → receiver marquee).
 */
object RoomGiftCatalog {
    val all: List<RoomGiftSku> =
        listOf(
            // ── Standard ─────────────────────────────────────────────
            RoomGiftSku("rose", "🌹", "وردة", "Rose", 1, 1, svga = "gifts/rose.svga", icon = "gifts/icons/rose.png", sound = "gift_rose"),
            RoomGiftSku("heart", "❤️", "قلب", "Heart", 1, 1, svga = "gifts/heartbeat.svga", icon = "gifts/icons/heart.png", sound = "gift_heart"),
            RoomGiftSku("star", "⭐", "نجمة", "Star", 1, 2, svga = "gifts/gen_star.svga", icon = "gifts/icons/star.png", sound = "gift_star"),
            RoomGiftSku("clap", "👏", "تصفيق", "Applause", 1, 2, svga = "gifts/gen_clap.svga", icon = "gifts/icons/clap.png", sound = "gift_clap"),
            RoomGiftSku("kiss", "💋", "قبلة", "Kiss", 1, 3, svga = "gifts/gen_kiss.svga", icon = "gifts/icons/kiss.png", sound = "gift_kiss"),
            RoomGiftSku("coffee", "☕", "قهوة", "Coffee", 1, 3, svga = "gifts/gen_coffee.svga", icon = "gifts/icons/coffee.png", sound = "gift_coffee"),
            RoomGiftSku("cake", "🎂", "كيك", "Birthday Cake", 1, 5, svga = "gifts/gen_cake.svga", icon = "gifts/icons/cake.png", sound = "gift_cake"),
            RoomGiftSku("icecream", "🍦", "مثلجات", "Ice Cream", 1, 5, svga = "gifts/gen_icecream.svga", icon = "gifts/icons/icecream.png", sound = "gift_icecream"),
            RoomGiftSku("beer", "🍺", "مشروب", "Cheers", 1, 6, svga = "gifts/gen_beer.svga", icon = "gifts/icons/beer.png", sound = "gift_beer"),
            RoomGiftSku("balloon", "🎈", "بالون", "Balloon", 1, 8, svga = "gifts/gen_balloon.svga", icon = "gifts/icons/balloon.png", sound = "gift_balloon"),
            // ── Premium ──────────────────────────────────────────────
            RoomGiftSku("gift", "🎁", "هدية", "Gift Box", 2, 20, svga = "gifts/christmas.svga", icon = "gifts/icons/gift.png", sound = "gift_gift"),
            RoomGiftSku("perfume", "🧴", "عطر", "Perfume", 2, 30, svga = "gifts/gen_perfume.svga", icon = "gifts/icons/perfume.png", sound = "gift_perfume"),
            RoomGiftSku("bouquet", "💐", "باقة ورد", "Bouquet", 2, 40, svga = "gifts/rose_v2.svga", icon = "gifts/icons/bouquet.png", sound = "gift_bouquet"),
            RoomGiftSku("trophy", "🏆", "كأس", "Trophy", 2, 66, svga = "gifts/gen_trophy.svga", icon = "gifts/icons/trophy.png", sound = "gift_trophy"),
            RoomGiftSku("crown", "👑", "تاج", "Crown", 2, 88, svga = "gifts/gen_crown.svga", icon = "gifts/icons/crown.png", sound = "gift_crown"),
            RoomGiftSku("guitar", "🎸", "قيثارة", "Guitar", 2, 99, svga = "gifts/gen_guitar.svga", icon = "gifts/icons/guitar.png", sound = "gift_guitar"),
            RoomGiftSku("diamond", "💎", "جوهرة", "Diamond", 2, 120, svga = "gifts/gen_diamond.svga", icon = "gifts/icons/diamond.png", sound = "gift_diamond"),
            RoomGiftSku("teddy", "🧸", "دمية", "Teddy", 2, 150, svga = "gifts/gen_teddy.svga", icon = "gifts/icons/teddy.png", sound = "gift_teddy"),
            RoomGiftSku("ring", "💍", "خاتم", "Ring", 2, 199, svga = "gifts/gen_ring.svga", icon = "gifts/icons/ring.png", sound = "gift_ring"),
            RoomGiftSku("phone", "📱", "جوال", "Phone", 2, 288, svga = "gifts/gen_phone.svga", icon = "gifts/icons/phone.png", sound = "gift_phone"),
            // ── Luxe (full-screen cinematic SVGA takeover) ───────────
            // The first five carry a ready-made high-resolution SVGA
            // clip imported verbatim from Tencent's MIT-licensed
            // TUILiveKit gift pack; the clip is named after the gift it
            // depicts.
            RoomGiftSku("car", "🚗", "سيارة", "Car", 3, 1314, svga = "gifts/car.svga", icon = "gifts/icons/car.png", sound = "gift_car"),
            RoomGiftSku("sportscar", "🏎️", "سيارة سباق", "Sports Car", 3, 2025, svga = "gifts/sports_car.svga", icon = "gifts/icons/sportscar.png", sound = "gift_sportscar"),
            RoomGiftSku("cat", "🐱", "قطة", "Cat", 3, 2525, svga = "gifts/cat.svga", icon = "gifts/icons/cat.png", sound = "gift_cat"),
            RoomGiftSku("angel", "👼", "ملاك", "Angel", 3, 3333, svga = "gifts/angel.svga", icon = "gifts/icons/angel.png", sound = "gift_angel"),
            RoomGiftSku("pin", "📍", "دبوس", "Pin", 3, 777, svga = "gifts/pin_jump.svga", icon = "gifts/icons/pin.png", sound = "gift_pin"),
            RoomGiftSku("rocket", "🚀", "صاروخ", "Rocket", 3, 520, svga = "gifts/rocket.svga", icon = "gifts/icons/rocket.png", sound = "gift_rocket"),
            RoomGiftSku("crownjewel", "👑", "تاج المرصع", "Jewelled Crown", 3, 888, svga = "gifts/gen_crownjewel.svga", icon = "gifts/icons/crownjewel.png", sound = "gift_crownjewel"),
            RoomGiftSku("castle", "🏰", "قلعة", "Castle", 3, 999, svga = "gifts/castle.svga", icon = "gifts/icons/castle.png", sound = "gift_castle"),
            RoomGiftSku("helicopter", "🚁", "هليكوبتر", "Helicopter", 3, 3000, svga = "gifts/gen_helicopter.svga", icon = "gifts/icons/helicopter.png", sound = "gift_helicopter"),
            RoomGiftSku("yacht", "🛥️", "يخت", "Yacht", 3, 5200, svga = "gifts/gen_yacht.svga", icon = "gifts/icons/yacht.png", sound = "gift_yacht"),
            // ── Legendary (mega takeover) ────────────────────────────
            RoomGiftSku("jet", "✈️", "طائرة خاصة", "Private Jet", 4, 8888, svga = "gifts/gen_jet.svga", icon = "gifts/icons/jet.png", sound = "gift_jet"),
            RoomGiftSku("lion", "🦁", "أسد", "Golden Lion", 4, 9999, svga = "gifts/gen_lion.svga", icon = "gifts/icons/lion.png", sound = "gift_lion"),
            RoomGiftSku("planet", "🪐", "كوكب", "Planet", 4, 13140, svga = "gifts/gen_planet.svga", icon = "gifts/icons/planet.png", sound = "gift_planet"),
            RoomGiftSku("galaxy", "🌌", "المجرة", "Galaxy", 4, 19999, svga = "gifts/gen_galaxy.svga", icon = "gifts/icons/galaxy.png", sound = "gift_galaxy"),
            RoomGiftSku("unicorn", "🦄", "يونيكورن", "Unicorn", 4, 23333, svga = "gifts/gen_unicorn.svga", icon = "gifts/icons/unicorn.png", sound = "gift_unicorn"),
            RoomGiftSku("dragon", "🐉", "تنين", "Dragon", 4, 52000, svga = "gifts/gen_dragon.svga", icon = "gifts/icons/dragon.png", sound = "gift_dragon"),
            RoomGiftSku("crownmega", "🏆", "التاج الأسطوري", "Legend Crown", 4, 88888, svga = "gifts/gen_crownmega.svga", icon = "gifts/icons/crownmega.png", sound = "gift_crownmega"),
            RoomGiftSku("galaxyrocket", "🚀", "صاروخ المجرة", "Galaxy Rocket", 4, 131400, svga = "gifts/gen_galaxyrocket.svga", icon = "gifts/icons/galaxyrocket.png", sound = "gift_galaxyrocket"),
        )

    val tiers: List<Int> = listOf(1, 2, 3, 4)

    fun byId(id: String): RoomGiftSku? = all.firstOrNull { it.id == id }
}

/**
 * One gift sent to a room participant, projected from the kind-7
 * reaction that carries it. A room gift rides the *existing*
 * reaction transport (no new relay subscription, no new event kind):
 * the sender publishes a normal NIP-25 kind-7 whose content is the
 * gift emoji and which carries a `["gift", skuId, quantity]` tag. A
 * plain reaction has no such tag and is handled by
 * [RoomReactionsAggregator] instead — see [giftPayload].
 */
@Immutable
data class RoomGift(
    /** Source kind-7 event id — dedup across relay re-emits. */
    val eventId: String,
    /** Who SENT the gift. */
    val sourcePubkey: String,
    /** Participant the gift names via its `p` tag, or `null` for the room. */
    val targetPubkey: String?,
    val skuId: String,
    val emoji: String,
    val quantity: Int,
    val createdAtSec: Long,
    /** Catalog tier (1..4), or 0 for an unknown/future SKU. */
    val tier: Int = 0,
) {
    companion object {
        fun from(event: ReactionEvent): RoomGift? {
            val payload = event.giftPayload() ?: return null
            return RoomGift(
                eventId = event.id,
                sourcePubkey = event.pubKey,
                targetPubkey = event.originalAuthor().firstOrNull(),
                skuId = payload.skuId,
                emoji = event.content,
                quantity = payload.quantity,
                createdAtSec = event.createdAt,
                tier = RoomGiftCatalog.byId(payload.skuId)?.tier ?: 0,
            )
        }
    }
}

/** Parsed `["gift", skuId, quantity]` tag payload. */
@Immutable
data class GiftPayload(
    val skuId: String,
    val quantity: Int,
)

/** Tag name marking a kind-7 as a room gift rather than a plain reaction. */
const val GIFT_TAG = "gift"

/**
 * Read the gift payload from a kind-7, or `null` when it's a plain
 * reaction. Tolerates a missing/!parsable quantity (defaults to 1) and
 * a malformed SKU (kept as-is so an unknown future SKU still renders
 * its emoji rather than being dropped).
 */
fun ReactionEvent.giftPayload(): GiftPayload? {
    val tag = tags.firstOrNull { it.size >= 2 && it[0] == GIFT_TAG } ?: return null
    return GiftPayload(
        skuId = tag[1],
        quantity = tag.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
    )
}

/**
 * Sliding-window aggregator for room gifts. Mirrors
 * [RoomZapsAggregator] — same dedup-by-event-id rule and
 * group-by-sender output shape — but with a longer window so a gift
 * animation gets time to play before the entry is evicted. Gifts are
 * grouped by [RoomGift.targetPubkey] (the recipient) so the overlay
 * can anchor the burst on the person who received it, matching
 * TRTC's `GiftPlayView` semantics.
 *
 * Not thread-safe — call from the VM's single coroutine.
 */
class RoomGiftsAggregator {
    private val byEventId = LinkedHashMap<String, RoomGift>()

    /** Apply one gift and return the post-evict snapshot. */
    fun apply(
        gift: RoomGift,
        nowSec: Long,
        windowSec: Long = GIFT_WINDOW_SEC,
    ): Map<String, List<RoomGift>> {
        byEventId[gift.eventId] = gift
        return evictAndSnapshot(nowSec - windowSec)
    }

    /**
     * Drop gifts older than [olderThanSec]. Caller drives the cadence
     * (typically every second) so the overlay's animation timing lives
     * in one place rather than per-component.
     */
    fun evictAndSnapshot(olderThanSec: Long): Map<String, List<RoomGift>> {
        val it = byEventId.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.createdAtSec < olderThanSec) it.remove()
        }
        return byEventId.values.groupBy { it.targetPubkey ?: "" }
    }

    fun isEmpty(): Boolean = byEventId.isEmpty()
}

/**
 * How long a gift stays in the aggregator. Longer than
 * [REACTION_WINDOW_SEC] (10 s) so a gift burst plays out fully — the
 * overlay caps its own per-burst animation at [GIFT_BURST_MS].
 */
const val GIFT_WINDOW_SEC: Long = 22L

/** Wall-clock duration of one gift burst animation (standard tier). */
const val GIFT_BURST_MS: Long = 2_600L

/**
 * Playback duration for a gift, scaled by tier so a legendary drop
 * gets a real cinematic beat instead of the standard blink. Mirrors
 * how TRTC maps a gift's price band to an SVGA clip length.
 */
fun giftBurstMillis(tier: Int): Long =
    when (tier) {
        4 -> 5_200L
        3 -> 4_200L
        2 -> 3_200L
        else -> GIFT_BURST_MS
    }
