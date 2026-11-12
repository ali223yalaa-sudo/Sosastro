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
package com.vitorpamplona.amethyst.service.notifications

import com.vitorpamplona.amethyst.commons.model.privateChats.DmHistoryTuning
import com.vitorpamplona.amethyst.commons.relayClient.nip17Dm.filterGiftWrapsToPubkey
import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.pool.groupByRelay
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A headless, Activity-independent gift-wrap subscription (kinds 1059 / 21059) on the
 * account's DM relays. This is what makes incoming calls and direct messages arrive while
 * the app has **no screen in the foreground** (cold start, swiped from recents, screen off).
 *
 * Why it exists: [NotificationRelayService] keeps the WebSocket pool alive, but the only
 * gift-wrap REQ used to be the Compose-tree `AccountFilterAssemblerSubscription`. That
 * subscription is torn down with the Activity, so with the app closed no gift wrap was ever
 * requested, nothing reached [com.vitorpamplona.amethyst.model.LocalCache], and neither the
 * call ring nor the DM notification could fire. Keeping the relay connected is not enough —
 * the REQ has to exist too.
 *
 * The delivery half already works headlessly: [com.vitorpamplona.amethyst.service.relayClient.CacheClientConnector]
 * funnels every subscription event into LocalCache, and the loaded account's
 * `newNotesPreProcessor` unwraps and decrypts it there, which is what fires
 * [NotificationDispatcher]. This class supplies only the missing REQ.
 *
 * The filter mirrors [com.vitorpamplona.amethyst.service.relayClient.reqCommand.account.nip59GiftWraps.AccountGiftWrapsEoseManager]:
 * a one-week floor with no upper bound, re-issued whenever the DM relay set changes.
 */
class AlwaysOnGiftWrapSubscription(
    private val client: INostrClient,
    private val account: Account,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AlwaysOnGiftWrap"
    }

    private var job: Job? = null
    private var subId: String? = null

    fun start() {
        if (job?.isActive == true) return
        Log.d(TAG) { "Starting headless gift-wrap subscription for ${account.signer.pubKey.take(8)}" }
        job =
            scope.launch {
                account.dmRelays.flow.collectLatest { relays ->
                    val since = DmHistoryTuning.recentBoundary()
                    val filters =
                        relays
                            .flatMap { relay ->
                                filterGiftWrapsToPubkey(
                                    relay = relay,
                                    pubkey = account.signer.pubKey,
                                    since = since,
                                )
                            }.groupByRelay()

                    val id = subId ?: newSubId().also { subId = it }
                    if (filters.isEmpty()) {
                        client.unsubscribe(id)
                    } else {
                        client.subscribe(id, filters)
                        Log.d(TAG) {
                            "[giftwrap.headless] REQ since=$since on ${relays.size} relay(s): ${relays.map { it.url }}"
                        }
                    }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        subId?.let { client.unsubscribe(it) }
        subId = null
    }
}
