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
package com.vitorpamplona.amethyst.ui.dal

import com.vitorpamplona.amethyst.commons.ui.notifications.Card
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.model.TopFilter
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl

val DefaultFeedOrder: Comparator<Note> =
    compareByDescending<Note> { it.createdAt() }.thenBy { it.idHex }

val DefaultFeedOrderEvent: Comparator<Event> =
    compareByDescending<Event> { it.createdAt }.thenBy { it.id }

val DefaultFeedOrderCard: Comparator<Card> =
    compareByDescending<Card> { it.createdAt() }.thenBy { it.id() }

// Snapshots createdAt once per note so the comparator stays consistent even if
// another thread swaps a Note's event mid-sort (e.g. a newer AddressableEvent
// arriving from a relay). Avoids TimSort's "Comparison method violates its
// general contract!" IllegalArgumentException.
fun Iterable<Note>.sortedByDefaultFeedOrder(): List<Note> =
    map { it to (it.createdAt() ?: 0L) }
        .sortedWith(compareByDescending<Pair<Note, Long>> { it.second }.thenBy { it.first.idHex })
        .map { it.first }

fun Iterable<Note>.sortedByInterestOrDefault(
    useInterestOrder: Boolean,
    accountPubkeyHex: String? = null,
    meLiveRelay: NormalizedRelayUrl? = null,
): List<Note> {
    if (!useInterestOrder) {
        return sortedByDefaultFeedOrder().diversifyByAuthor()
    }
    val all = this.toList()
    val ranked =
        com.vitorpamplona.amethyst.commons.ui.feeds.ServedFeedOrder
            .ordered(all)
    val rankedIds = ranked.map { it.idHex }.toSet()
    val ownUnranked =
        if (accountPubkeyHex != null) {
            all
                .filter { it.author?.pubkeyHex == accountPubkeyHex && it.idHex !in rankedIds }
                .sortedByDescending { it.createdAt() ?: 0L }
        } else {
            emptyList()
        }
    // Recommendations first, then the author's own unranked posts, then other ME live
    // content as lower-priority filler — never ahead of true recommendations.
    if (ranked.isNotEmpty()) {
        val otherUnranked =
            if (meLiveRelay != null) {
                all
                    .filter {
                        it.idHex !in rankedIds &&
                            it.author?.pubkeyHex != accountPubkeyHex &&
                            it.relays.contains(meLiveRelay)
                    }.sortedByDefaultFeedOrder()
            } else {
                emptyList()
            }
        return (
            ranked.diversifyByAuthor() +
                ownUnranked.diversifyByAuthor() +
                otherUnranked.diversifyByAuthor()
        ).distinctBy { it.idHex }
    }
    // Cold start / no similar yet: chronological ME (or all) without recommendation badges.
    val meOnly =
        if (meLiveRelay != null) {
            all
                .filter { it.relays.contains(meLiveRelay) || it.author?.pubkeyHex == accountPubkeyHex }
                .sortedByDefaultFeedOrder()
        } else {
            all.sortedByDefaultFeedOrder()
        }
    return meOnly.diversifyByAuthor()
}

/**
 * Round-robin authors so one person posting 50 notes cannot occupy the whole
 * viewport. Notes are not dropped — only interleaved. A single-author list
 * (profile, or only one poster in cache) is left unchanged.
 */
fun List<Note>.diversifyByAuthor(): List<Note> {
    if (size < 3) return this
    val queues = LinkedHashMap<String, ArrayDeque<Note>>()
    for (note in this) {
        val key = note.author?.pubkeyHex ?: note.idHex
        queues.getOrPut(key) { ArrayDeque() }.addLast(note)
    }
    if (queues.size <= 1) return this
    val out = ArrayList<Note>(size)
    while (queues.isNotEmpty()) {
        val iter = queues.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            out.add(entry.value.removeFirst())
            if (entry.value.isEmpty()) iter.remove()
        }
    }
    return out
}

fun TopFilter.usesAstroInterestOrder(): Boolean = this == TopFilter.MiddleEast || this == TopFilter.Trending

/** Keep the author's own uploads visible in Stories/Posts while algo batches rotate. */
fun List<Note>.withOwnPostsPinnedFirst(accountPubkeyHex: String): List<Note> {
    if (isEmpty()) return this
    val (mine, rest) = partition { it.author?.pubkeyHex == accountPubkeyHex }
    return mine.sortedByDescending { it.createdAt() ?: 0L } + rest
}
