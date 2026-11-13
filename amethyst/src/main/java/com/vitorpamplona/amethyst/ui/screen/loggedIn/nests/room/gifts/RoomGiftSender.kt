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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts

import com.vitorpamplona.amethyst.commons.viewmodels.GIFT_TAG
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGiftSku
import com.vitorpamplona.amethyst.model.AddressableNote
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.aTag
import com.vitorpamplona.quartz.nip01Core.tags.events.eTag
import com.vitorpamplona.quartz.nip01Core.tags.events.toETag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.kind
import com.vitorpamplona.quartz.nip01Core.tags.people.pTag
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent

/**
 * Publish a room gift. A gift is a NIP-25 kind-7 reaction whose
 * content is the gift emoji and which carries a `["gift", skuId,
 * quantity]` tag; receivers distinguish it from a plain reaction by
 * that tag (see [com.vitorpamplona.amethyst.commons.viewmodels.giftPayload]).
 *
 * Built by hand rather than via [ReactionEvent.build] because we need
 * to append the extra `gift` tag, and published via
 * `signAndComputeBroadcast` so it lands in LocalCache and on the
 * user's default relays exactly like a reaction — no dedicated
 * subscription or relay set is required.
 */
fun sendRoomGift(
    accountViewModel: AccountViewModel,
    roomNote: AddressableNote,
    sku: RoomGiftSku,
    quantity: Int,
    targetPubkey: String? = null,
) {
    val eventHint = roomNote.toEventHint<Event>() ?: return
    val target = eventHint.event
    accountViewModel.launchSigner {
        val template =
            eventTemplate<ReactionEvent>(ReactionEvent.KIND, sku.emoji) {
                eTag(eventHint.toETag())
                if (target is AddressableEvent) {
                    aTag(target.address(), eventHint.relay)
                }
                pTag(targetPubkey ?: target.pubKey, eventHint.relay)
                kind(target.kind)
                add(arrayOf(GIFT_TAG, sku.id, quantity.toString()))
            }
        accountViewModel.account.signAndComputeBroadcast(template)
    }
}
