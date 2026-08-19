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
package com.vitorpamplona.amethyst.commons.ui.feeds

import com.vitorpamplona.amethyst.commons.model.Note
import com.vitorpamplona.amethyst.commons.util.KmpLock
import com.vitorpamplona.amethyst.commons.util.withLock

/**
 * Preserves the order events were served by an algorithmic relay (e.g. Astro algo-relay).
 * Default feed filters re-sort by createdAt and would destroy interest ranking without this.
 */
object ServedFeedOrder {
    data class Rank(
        val generation: Long,
        val index: Int,
    )

    private val lock = KmpLock()
    private val ranks = mutableMapOf<String, Rank>()
    private var generation = 0L
    private var nextIndex = 0

    /** Call when the client is about to REQ a fresh interest batch (pull-to-refresh / invalidate). */
    fun beginBatch() {
        lock.withLock {
            generation += 1
            nextIndex = 0
        }
    }

    /** Call when an event arrives from the algorithmic relay, in serve order. */
    fun record(noteId: String) {
        lock.withLock {
            val existing = ranks[noteId]
            if (existing != null && existing.generation == generation) return@withLock
            ranks[noteId] = Rank(generation, nextIndex)
            nextIndex += 1
        }
    }

    /** True if [noteId] was ranked in the current serve generation. */
    fun contains(noteId: String): Boolean =
        lock.withLock {
            ranks[noteId]?.generation == generation
        }

    fun ordered(items: Collection<Note>): List<Note> {
        if (items.isEmpty()) return emptyList()
        val (snapshotRanks, g) =
            lock.withLock {
                ranks.toMap() to generation
            }
        val current =
            items.mapNotNull { note ->
                val r = snapshotRanks[note.idHex]
                if (r != null && r.generation == g) note to r.index else null
            }
        if (current.isNotEmpty()) {
            return current.sortedBy { it.second }.map { it.first }
        }
        // While the new batch is still arriving, keep previous interest order.
        val previous =
            items.mapNotNull { note ->
                snapshotRanks[note.idHex]?.let { note to it }
            }
        if (previous.isNotEmpty()) {
            return previous
                .sortedWith(
                    compareByDescending<Pair<Note, Rank>> { it.second.generation }
                        .thenBy { it.second.index },
                ).map { it.first }
        }
        // Never fall back to full-cache chronology — only show notes the algo relay actually ranked.
        val anyRanked =
            items.mapNotNull { note ->
                snapshotRanks[note.idHex]?.let { note to it }
            }
        if (anyRanked.isNotEmpty()) {
            return anyRanked
                .sortedWith(
                    compareByDescending<Pair<Note, Rank>> { it.second.generation }
                        .thenBy { it.second.index },
                ).map { it.first }
        }
        return emptyList()
    }
}

/** Feed filters that should keep algorithmic relay order instead of chronology. */
interface RelayOrderFeedFilter {
    fun prefersServedOrder(): Boolean
}
