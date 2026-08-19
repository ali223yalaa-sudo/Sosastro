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
package com.vitorpamplona.amethyst.model.stars

import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StarsWalletState(
    private val account: Account,
    private val scope: CoroutineScope,
    private val api: StarsApiClient = StarsApiClient(),
) {
    private val _snapshot = MutableStateFlow(StarsSnapshot())
    val snapshot: StateFlow<StarsSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    suspend fun refresh() {
        val catalog = api.fetchCatalog().getOrNull()
        val wallet = api.fetchWallet(account).getOrNull()
        _snapshot.update { current ->
            StarsSnapshot(
                stars = wallet?.stars ?: current.stars,
                inventory = wallet?.inventory ?: current.inventory,
                catalog = catalog ?: current.catalog,
                dailyClaimed = wallet?.dailyClaimed ?: current.dailyClaimed,
                dailyStars = wallet?.dailyStars ?: catalog?.dailyStars ?: current.dailyStars,
                dayUtc = wallet?.dayUtc ?: current.dayUtc,
            )
        }
    }

    suspend fun claimDailyGift(): Result<Unit> {
        val result = api.claimDaily(account)
        result.onSuccess { claimed ->
            _snapshot.update {
                it.copy(
                    stars = claimed.stars,
                    dailyClaimed = true,
                    dailyStars = claimed.dailyStars.takeIf { n -> n > 0 } ?: it.dailyStars,
                    dayUtc = claimed.dayUtc.ifBlank { it.dayUtc },
                )
            }
            refresh()
        }
        return result.map { }
    }

    suspend fun buyGift(skuId: String): Result<Unit> {
        val result = api.buyGift(account, skuId)
        result.onSuccess { bought ->
            _snapshot.update { it.copy(stars = bought.stars, inventory = bought.inventory.ifEmpty { it.inventory }) }
            refresh()
        }
        return result.map { }
    }

    suspend fun sendGift(
        skuId: String,
        toPubkey: String,
        noteId: String?,
    ): Result<Unit> {
        val result = api.sendGift(account, skuId, toPubkey, noteId)
        result.onSuccess {
            refresh()
        }
        result.onFailure { Log.w("StarsWallet", "send failed: ${it.message}") }
        return result.map { }
    }

    suspend fun requestStarPackage(packageId: String): Result<Unit> {
        val result = api.createStarTopup(account, packageId)
        return result.map { }
    }

    suspend fun noteGifts(noteId: String): Result<NoteGiftSummary> = api.fetchNoteGifts(noteId)

    suspend fun profileGifts(pubkeyHex: String): Result<NoteGiftSummary> {
        val remote = api.fetchProfileGifts(pubkeyHex)
        if (remote.isSuccess) return remote
        Log.w("StarsWallet") { "profile gifts API failed, aggregating from notes: ${remote.exceptionOrNull()?.message}" }
        return Result.success(aggregateGiftsFromAuthorNotes(pubkeyHex))
    }

    private suspend fun aggregateGiftsFromAuthorNotes(pubkeyHex: String): NoteGiftSummary {
        val target = pubkeyHex.lowercase()
        val ids = ArrayList<String>()
        LocalCache.notes.forEach { id, note ->
            if (id.length == 64 && note.author?.pubkeyHex?.lowercase() == target) {
                ids.add(id)
            }
        }
        val bySku = mutableMapOf<String, Int>()
        var total = 0
        coroutineScope {
            ids.take(80).chunked(8).forEach { chunk ->
                chunk
                    .map { id -> async(Dispatchers.IO) { api.fetchNoteGifts(id).getOrNull() } }
                    .awaitAll()
                    .forEach { summary ->
                        if (summary != null && summary.total > 0) {
                            total += summary.total
                            summary.bySku.forEach { (sku, count) ->
                                bySku[sku] = (bySku[sku] ?: 0) + count
                            }
                        }
                    }
            }
        }
        return NoteGiftSummary(total = total, bySku = bySku)
    }
}
