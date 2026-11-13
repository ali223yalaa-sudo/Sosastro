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

import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomGiftsStateTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val charlie = "c".repeat(64)

    private var nextEventId = 1

    private fun reaction(
        from: String,
        to: String?,
        content: String,
        createdAt: Long,
        giftTag: Array<String>? = null,
        id: String = (nextEventId++).toString(16).padStart(64, '0'),
    ): ReactionEvent {
        val tags =
            buildList<Array<String>> {
                add(arrayOf("a", "30312:host:room"))
                if (to != null) add(arrayOf("p", to))
                if (giftTag != null) add(giftTag)
            }.toTypedArray()
        return ReactionEvent(
            id = id,
            pubKey = from,
            createdAt = createdAt,
            tags = tags,
            content = content,
            sig = "0".repeat(128),
        )
    }

    @Test
    fun plainReactionHasNoGiftPayload() {
        val rxn = reaction(alice, bob, "🔥", 100L)
        assertNull(rxn.giftPayload())
        assertNull(RoomGift.from(rxn))
    }

    @Test
    fun giftPayloadParsesSkuAndQuantity() {
        val payload = reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "3")).giftPayload()
        assertNotNull(payload)
        assertEquals("rose", payload.skuId)
        assertEquals(3, payload.quantity)
    }

    @Test
    fun giftPayloadDefaultsQuantityToOneWhenMissing() {
        assertEquals(1, reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose")).giftPayload()?.quantity)
    }

    @Test
    fun giftPayloadDefaultsQuantityToOneWhenUnparsable() {
        assertEquals(1, reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "abc")).giftPayload()?.quantity)
    }

    @Test
    fun giftPayloadRejectsZeroOrNegativeQuantity() {
        assertEquals(1, reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "0")).giftPayload()?.quantity)
        assertEquals(1, reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "-5")).giftPayload()?.quantity)
    }

    @Test
    fun fromEventProjectsSenderRecipientAndSku() {
        val gift = RoomGift.from(reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "2")))
        assertNotNull(gift)
        assertEquals(alice, gift.sourcePubkey)
        assertEquals(bob, gift.targetPubkey)
        assertEquals("rose", gift.skuId)
        assertEquals("🌹", gift.emoji)
        assertEquals(2, gift.quantity)
        assertEquals(100L, gift.createdAtSec)
    }

    @Test
    fun fromEventWithoutRecipientGroupsUnderRoomKey() {
        val gift = RoomGift.from(reaction(alice, null, "⭐", 100L, arrayOf(GIFT_TAG, "star", "1")))
        assertNotNull(gift)
        assertNull(gift.targetPubkey)

        val agg = RoomGiftsAggregator()
        val snapshot = agg.apply(gift, nowSec = 100L)
        assertEquals(listOf(gift), snapshot[""])
    }

    @Test
    fun aggregatorGroupsByRecipient() {
        val agg = RoomGiftsAggregator()
        val toBob = RoomGift.from(reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "1")))!!
        val toCharlie = RoomGift.from(reaction(alice, charlie, "🏆", 101L, arrayOf(GIFT_TAG, "trophy", "1")))!!

        agg.apply(toBob, nowSec = 101L)
        val snapshot = agg.apply(toCharlie, nowSec = 101L)

        assertEquals(1, snapshot[bob]?.size)
        assertEquals(1, snapshot[charlie]?.size)
    }

    @Test
    fun aggregatorDedupsByEventId() {
        val agg = RoomGiftsAggregator()
        val id = "f".repeat(64)
        val first = RoomGift.from(reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "1"), id = id))!!
        val reEmit = RoomGift.from(reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "1"), id = id))!!

        agg.apply(first, nowSec = 100L)
        val snapshot = agg.apply(reEmit, nowSec = 100L)

        assertEquals(1, snapshot[bob]?.size)
    }

    @Test
    fun aggregatorEvictsOldGifts() {
        val agg = RoomGiftsAggregator()
        val old = RoomGift.from(reaction(alice, bob, "🌹", 10L, arrayOf(GIFT_TAG, "rose", "1")))!!
        val fresh = RoomGift.from(reaction(alice, bob, "🌹", 100L, arrayOf(GIFT_TAG, "rose", "1")))!!

        agg.apply(old, nowSec = 100L, windowSec = GIFT_WINDOW_SEC)
        val snapshot = agg.apply(fresh, nowSec = 100L, windowSec = GIFT_WINDOW_SEC)

        assertEquals(listOf(fresh), snapshot[bob])
    }

    @Test
    fun catalogIsWellFormed() {
        val ids = RoomGiftCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "gift ids must be unique")
        assertTrue(RoomGiftCatalog.all.all { it.emoji.isNotBlank() })
        assertTrue(RoomGiftCatalog.all.all { it.tier in RoomGiftCatalog.tiers })
        RoomGiftCatalog.all.forEach { sku ->
            assertEquals(sku, RoomGiftCatalog.byId(sku.id))
        }
    }

    @Test
    fun catalogLocalizesLabels() {
        val rose = RoomGiftCatalog.byId("rose")!!
        assertEquals("Rose", rose.label(arabic = false))
        assertEquals("وردة", rose.label(arabic = true))
    }
}
