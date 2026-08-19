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
package com.vitorpamplona.amethyst.service.relayClient

import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.EventCollector
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.ParallelEventVerifier
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.RelayInsertConfirmationCollector
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import kotlinx.coroutines.CoroutineScope

class CacheClientConnector(
    val client: INostrClient,
    val cache: LocalCache,
    scope: CoroutineScope,
) {
    // Schnorr verify off the per-relay receive coroutine. Duplicates already
    // in LocalCache skip the CPU and still go through justConsume so relay
    // attribution is recorded. New events are batched and verified in parallel.
    private val verifier =
        ParallelEventVerifier<IRelayClient>(
            scope = scope,
            preVerified = cache::hasConsumed,
            onVerified = { event, relay ->
                cache.justConsume(event, relay, wasVerified = true)
            },
        )

    val receiver =
        EventCollector(client) { event, relay ->
            if (cache.hasConsumed(event)) {
                cache.justConsume(event, relay, wasVerified = true)
            } else {
                verifier.submit(event, relay)
            }
        }

    // markAsSeen drills into the gift-wrap chain (wrap → seal → rumor) via
    // LocalCache.addRelayToNoteAndInners, so an OK acceptance for a wrap also
    // tags the inner rumor note the chat UI renders.
    val confirmationWatcher =
        RelayInsertConfirmationCollector(client) { eventId, relay ->
            cache.markAsSeen(eventId, relay.url)
        }

    fun destroy() {
        receiver.destroy()
        confirmationWatcher.destroy()
        verifier.close()
    }
}
