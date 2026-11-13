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
import com.vitorpamplona.quartz.nip53LiveActivities.presence.MeetingRoomPresenceEvent

/**
 * One peer's most recent kind-10312 presence in a room — the
 * aggregator keeps a `Map<pubkey, RoomPresence>` populated from
 * the relay subscription, and the participant grid + listener
 * counter render off it.
 *
 * Standard data-class equality (all fields). A pubkey-only equals
 * was tempting but breaks `Map.equals(Map)` change detection on a
 * heartbeat-only update — `StateFlow` would suppress the emission
 * because old.equals(new) returns true even though
 * handRaised / publishing flipped.
 */
@Immutable
data class RoomPresence(
    val pubkey: String,
    val handRaised: Boolean,
    val muted: Boolean?,
    val publishing: Boolean,
    val onstage: Boolean,
    val micSeat: Int? = null,
    val updatedAtSec: Long,
    /** Terminal leave — occupancy and mic seats MUST drop this pubkey. */
    val left: Boolean = false,
) {
    /** True while this peer still counts as being inside the room. */
    fun isOccupying(): Boolean = !left

    companion object {
        /**
         * Project a kind-10312 event into a [RoomPresence]. Tags missing
         * from the wire default to a "not advertising" state:
         *   - `handRaised` and `publishing` default to `false`
         *   - `muted` stays `null` so the UI can render "unknown" vs
         *     "explicitly false"
         *   - `onstage` defaults to `true` (a peer publishing kind-10312
         *     for a room is on the stage by definition unless they say
         *     otherwise; old clients that never emit `onstage` still
         *     show up as speakers)
         *   - `micSeat` stays `null` when the peer hasn't claimed a seat
         */
        fun from(event: MeetingRoomPresenceEvent): RoomPresence =
            RoomPresence(
                pubkey = event.pubKey,
                handRaised = event.handRaised() == true,
                muted = event.muted(),
                publishing = event.publishing() == true,
                onstage = event.onstage() ?: true,
                micSeat = event.micSeat(),
                updatedAtSec = event.createdAt,
                left = event.left() == true,
            )
    }
}

/**
 * In-memory aggregator for `Map<pubkey, RoomPresence>`. Tests and the
 * VM both call this directly; the input is a stream of presence events
 * from `LocalCache` (filtered by `#a` tag matching the current room).
 *
 * Dedupes by pubkey, keeping the most recent `createdAt`. Staleness
 * eviction is the caller's job — call [evictOlderThan] on a tick.
 */
class RoomPresenceAggregator {
    private val byPubkey = mutableMapOf<String, RoomPresence>()

    /**
     * Wall-clock second of the most recent terminal leave per pubkey. A
     * kind-10312 presence is a *replaceable* event keyed by `(kind,
     * pubkey)`, and `created_at` has 1-second resolution, so a leave can
     * share a second with the peer's last heartbeat — or arrive after it
     * on a different relay. Without this ledger a late heartbeat would
     * resurrect a peer who already left (the "ghost avatar that never
     * disappears" bug), and a same-second leave would be swallowed by
     * the stale-event guard. A leave is authoritative for its second;
     * only a strictly newer presence (a genuine re-join) clears it.
     */
    private val leftAt = mutableMapOf<String, Long>()

    /**
     * Apply one presence event. Returns a fresh snapshot when state
     * actually changed, or `null` when the event was a no-op (older
     * or equal to the cached presence for the same pubkey).
     *
     * `LocalCache.observeEvents` re-emits the full matching list on
     * every cache mutation, so in a 200-peer room the VM forwards
     * 200 events for a single new heartbeat. Returning `null` for
     * the 199 no-op replays lets the VM skip the
     * `_presences.value = ...` write — saves both the snapshot copy
     * and the StateFlow equality check (both O(N) per call).
     */
    fun applyOrNull(event: MeetingRoomPresenceEvent): Map<String, RoomPresence>? {
        val incoming = RoomPresence.from(event)
        val prevLeft = leftAt[incoming.pubkey]

        if (incoming.left) {
            // Out-of-order older leave must not undo a newer re-join.
            if (prevLeft != null && incoming.updatedAtSec < prevLeft) return null
            val current = byPubkey[incoming.pubkey]
            if (current == null) {
                leftAt[incoming.pubkey] = maxOf(prevLeft ?: Long.MIN_VALUE, incoming.updatedAtSec)
                return null
            }
            // A leave at the same second as the last heartbeat still wins:
            // the peer is gone, and waiting for the next heartbeat would
            // leave their avatar on screen for up to a full eviction window.
            if (incoming.updatedAtSec < current.updatedAtSec) return null
            byPubkey.remove(incoming.pubkey)
            leftAt[incoming.pubkey] = incoming.updatedAtSec
            return snapshot()
        }

        // Ignore any presence that predates (or ties) a terminal leave so a
        // late heartbeat can't resurrect a departed peer.
        if (prevLeft != null && incoming.updatedAtSec <= prevLeft) return null
        val current = byPubkey[incoming.pubkey]
        if (current != null && current.updatedAtSec >= incoming.updatedAtSec) {
            return null
        }
        if (prevLeft != null) leftAt.remove(incoming.pubkey)
        byPubkey[incoming.pubkey] = incoming
        return snapshot()
    }

    /** Apply-or-no-op variant kept for tests + callers that always want a snapshot. */
    fun apply(event: MeetingRoomPresenceEvent): Map<String, RoomPresence> {
        applyOrNull(event)
        return snapshot()
    }

    /**
     * Drop any peer whose last heartbeat is older than [olderThanSec].
     * Returns the post-eviction snapshot. nostrnests heartbeats every
     * 30 s; pass `now - 2 * 60` to evict a peer that's been silent for
     * two minutes (four missed heartbeats) — the fallback for a peer
     * whose explicit `left` never reached us because its process was
     * killed before it could publish one.
     */
    fun evictOlderThan(olderThanSec: Long): Map<String, RoomPresence> {
        val it = byPubkey.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.updatedAtSec < olderThanSec) {
                it.remove()
            }
        }
        leftAt.entries.removeAll { it.value < olderThanSec }
        return snapshot()
    }

    /** Read-only copy of the current state. */
    fun snapshot(): Map<String, RoomPresence> = byPubkey.toMap()

    /** Test / optimistic-UI path — overwrite without createdAt gating. */
    fun forcePut(presence: RoomPresence) {
        if (presence.left) {
            byPubkey.remove(presence.pubkey)
            leftAt[presence.pubkey] = presence.updatedAtSec
        } else {
            byPubkey[presence.pubkey] = presence
            leftAt.remove(presence.pubkey)
        }
    }
}
