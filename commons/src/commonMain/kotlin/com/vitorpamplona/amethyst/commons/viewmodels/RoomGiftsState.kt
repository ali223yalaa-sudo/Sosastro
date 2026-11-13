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
    /** 1 = everyday, 2 = premium, 3 = legendary. Drives the panel tab. */
    val tier: Int,
) {
    fun label(arabic: Boolean): String = if (arabic) labelAr else labelEn
}

/**
 * The built-in room gift catalog. Ordered so the panel's cheapest
 * tier reads first. Kept in `commons` (not the Android UI) so the
 * send path, the panel and the overlay all resolve the same SKU by
 * id, and so a unit test can assert the catalog is well-formed.
 */
object RoomGiftCatalog {
    val all: List<RoomGiftSku> =
        listOf(
            RoomGiftSku("rose", "🌹", "وردة", "Rose", 1),
            RoomGiftSku("heart", "❤️", "قلب", "Heart", 1),
            RoomGiftSku("star", "⭐", "نجمة", "Star", 1),
            RoomGiftSku("clap", "👏", "تصفيق", "Applause", 1),
            RoomGiftSku("cake", "🎂", "كيك", "Cake", 1),
            RoomGiftSku("coffee", "☕", "قهوة", "Coffee", 1),
            RoomGiftSku("trophy", "🏆", "كأس", "Trophy", 2),
            RoomGiftSku("crown", "👑", "تاج", "Crown", 2),
            RoomGiftSku("ring", "💎", "جوهرة", "Diamond", 2),
            RoomGiftSku("rocket", "🚀", "صاروخ", "Rocket", 2),
            RoomGiftSku("castle", "🏰", "قلعة", "Castle", 3),
            RoomGiftSku("fireworks", "🎆", "ألعاب نارية", "Fireworks", 3),
        )

    val tiers: List<Int> = listOf(1, 2, 3)

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
const val GIFT_WINDOW_SEC: Long = 14L

/** Wall-clock duration of one gift burst animation. */
const val GIFT_BURST_MS: Long = 2_600L
