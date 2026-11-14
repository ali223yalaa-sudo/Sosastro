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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ParticipantTag
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ROLE

/**
 * One row in the room's participant grid. Combines the static
 * `p`-tag role (from the kind-30312 event) with the dynamic
 * presence flags (from the per-pubkey kind-10312 aggregator).
 *
 * Pure data — UI just renders it.
 */
@Immutable
data class RoomMember(
    val pubkey: String,
    /** From the kind-30312 `p`-tag; null when the user is pure audience. */
    val role: ROLE?,
    /** Last seen second from the kind-10312 ledger; null when the user has never emitted presence. */
    val lastSeenSec: Long?,
    val handRaised: Boolean,
    val muted: Boolean?,
    val publishing: Boolean,
    /**
     * `true` when the user is in `event.participants()` BUT has no
     * recent presence in the aggregator. nostrnests greys out these
     * "members never joined" — match for parity.
     */
    val absent: Boolean,
)

/** Two-column layout: speakers up top, audience below. */
@Immutable
data class ParticipantGrid(
    val onStage: List<RoomMember>,
    val audience: List<RoomMember>,
) {
    companion object {
        val Empty = ParticipantGrid(emptyList(), emptyList())
    }
}

/**
 * Pure projection — given the event's participants + the
 * per-pubkey presence map, build the grid.
 *
 *   * On-stage: anyone whose `p`-tag role is HOST/MODERATOR/SPEAKER
 *     AND whose latest presence advertises `onstage != false`. A
 *     speaker who explicitly emitted `onstage=0` ("step off the
 *     stage") drops to audience without losing their role tag.
 *
 *     Caveat: a presence event is only honoured for the on-stage
 *     gate when it's NEWER than the role grant ([roleGrantSec],
 *     conservatively the kind-30312 `created_at`). Otherwise a
 *     freshly-promoted audience member whose pre-promotion
 *     `kind-10312` carried `onstage=0` would be pinned to the
 *     audience tab until they re-heartbeat — which on nostrnests
 *     never happens, since audience members there don't toggle
 *     their `onstage` flag back on after a role bump. Stale
 *     presence ⇒ trust the role.
 *   * Audience: every pubkey with recent presence that isn't on
 *     stage, plus participant-tagged users who haven't emitted
 *     presence yet (rendered as `absent = true`).
 *
 * [hostPubkey] is the kind-30312 event signer. Occupancy is presence-
 * only: p-tags without a live heartbeat do not mint a ghost person,
 * and a `left=1` presence is dropped immediately. The author is still
 * treated as HOST when they actually join (role grant), but they are
 * not auto-inserted into the room.
 */
fun buildParticipantGrid(
    participants: List<ParticipantTag>,
    presences: Map<String, RoomPresence>,
    hostPubkey: HexKey? = null,
    roleGrantSec: Long = 0L,
    openMic: Boolean = false,
): ParticipantGrid {
    val occupying = presences.filterValues { it.isOccupying() }
    val roles =
        buildMap {
            for (p in participants) {
                put(p.pubKey, p)
            }
            if (hostPubkey != null && hostPubkey !in this) {
                put(hostPubkey, ParticipantTag(hostPubkey, null, ROLE.HOST.code, null))
            }
        }

    val onStage = mutableListOf<RoomMember>()
    val audience = mutableListOf<RoomMember>()

    for ((pubkey, pres) in occupying) {
        val p = roles[pubkey]
        val role = p?.effectiveRole() ?: if (pubkey == hostPubkey) ROLE.HOST else null
        val canSpeak = p?.canSpeak() == true || role == ROLE.HOST
        // A live self-claim (a seat index + `onstage`) is positive,
        // peer-authored intent: honour it regardless of the role-grant
        // clock. Otherwise a host who republishes the room (which bumps
        // `created_at` past the guest's last heartbeat) would drop the
        // guest off-stage until their next 15-s heartbeat — the
        // "guest flashes in and out of the stage" report.
        val liveSelfClaim = pres.onstage && pres.micSeat != null
        val presenceIsFresh = pres.updatedAtSec >= roleGrantSec || liveSelfClaim
        val onstageFlag = if (presenceIsFresh) pres.onstage else canSpeak
        val member =
            RoomMember(
                pubkey = pubkey,
                role = role,
                lastSeenSec = pres.updatedAtSec,
                handRaised = pres.handRaised,
                muted = pres.muted,
                publishing = pres.publishing,
                absent = false,
            )
        // A live self-claim (`micSeat != null` + `onstage`) is a positive,
        // peer-authored intent to be on the mic. Honour it regardless of the
        // open-mic flag or the role-grant clock: a guest may step up in any
        // room (open or closed mic, even an empty one) by taking a seat, and
        // the host keeps control through seat locks + remove/mute. Without
        // this a guest who self-claimed would be silently held in the audience.
        val selfClaimOnStage =
            presenceIsFresh &&
                onstageFlag &&
                pres.micSeat != null
        if ((canSpeak && onstageFlag) || selfClaimOnStage) onStage += member else audience += member
    }
    return ParticipantGrid(onStage = onStage, audience = audience)
}

/**
 * Resolve the 10-mic seat map from explicit claims only: host
 * kind-30312 `mic` tags win, then presence self-claims (earliest per
 * seat). Does NOT auto-fill from on-stage roles — ascent is intentional.
 */
fun resolveMicSeats(
    hostSeats: Map<Int, HexKey>,
    presences: Map<String, RoomPresence>,
    onStagePubkeys: List<HexKey> = emptyList(),
): Map<Int, HexKey> {
    val occupying = presences.filterValues { it.isOccupying() }
    val out = linkedMapOf<Int, HexKey>()
    hostSeats.forEach { (seat, pubkey) ->
        val pres = occupying[pubkey] ?: return@forEach
        if (!pres.onstage && pres.micSeat == null) return@forEach
        out[seat] = pubkey
    }

    val seated = out.values.toMutableSet()
    occupying.values
        .filter { it.micSeat != null && it.onstage }
        .sortedBy { it.updatedAtSec }
        .forEach { pres ->
            val seat = pres.micSeat ?: return@forEach
            if (seat in out) return@forEach
            if (pres.pubkey in seated) return@forEach
            out[seat] = pres.pubkey
            seated += pres.pubkey
        }
    return out
}
