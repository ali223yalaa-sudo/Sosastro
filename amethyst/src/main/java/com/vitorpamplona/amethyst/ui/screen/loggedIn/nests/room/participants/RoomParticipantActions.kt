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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.participants

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.endpoint
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.image
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.micLocks
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.micSeats
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.openMic
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.participants
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.relays
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.summary
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.tags.MicSeatTag
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.tags.StatusTag
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ParticipantTag
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ROLE
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Pure builders for participant-list / mic-seat mutations on a kind-30312
 * audio room. All return a republish template with the SAME `d`-tag.
 *
 * Existing promote / demote / kick builders are preserved. Seat helpers
 * layer fixed 10-mic occupancy on top without removing those paths.
 */
internal object RoomParticipantActions {
    fun setRole(
        original: MeetingSpaceEvent,
        targetPubkey: String,
        newRole: ROLE,
    ): EventTemplate<MeetingSpaceEvent>? {
        val all = original.participants()
        val targetExisting = all.firstOrNull { it.pubKey == targetPubkey }
        if (targetExisting?.role.equals(ROLE.HOST.code, ignoreCase = true) && newRole != ROLE.HOST) {
            return null
        }
        if (newRole == ROLE.HOST && targetPubkey != original.pubKey) {
            return null
        }

        val mutated =
            if (targetExisting != null) {
                all.map {
                    if (it.pubKey == targetPubkey) it.copy(role = newRole.code) else it
                }
            } else {
                all + ParticipantTag(targetPubkey, null, newRole.code, null)
            }
        // Demote clears any seat; promote does NOT auto-claim a seat —
        // ascent is always intentional via claimSeat / presence.
        val seats =
            when (newRole) {
                ROLE.PARTICIPANT -> original.micSeats().filterValues { it != targetPubkey }
                ROLE.SPEAKER, ROLE.MODERATOR, ROLE.HOST -> original.micSeats()
            }
        return rebuild(original, mutated, original.status() ?: StatusTag.STATUS.LIVE, seats, original.openMic(), original.lockedMicSeats())
    }

    fun demoteToListener(
        original: MeetingSpaceEvent,
        targetPubkey: String,
    ): EventTemplate<MeetingSpaceEvent>? {
        val existing = original.participants().firstOrNull { it.pubKey == targetPubkey } ?: return null
        if (existing.role.equals(ROLE.PARTICIPANT.code, ignoreCase = true)) return null
        return setRole(original, targetPubkey, ROLE.PARTICIPANT)
    }

    fun removeParticipant(
        original: MeetingSpaceEvent,
        targetPubkey: String,
    ): EventTemplate<MeetingSpaceEvent>? {
        val all = original.participants()
        val target = all.firstOrNull { it.pubKey == targetPubkey } ?: return null
        if (target.role.equals(ROLE.HOST.code, ignoreCase = true)) return null
        val remaining = all.filterNot { it.pubKey == targetPubkey }
        val seats = original.micSeats().filterValues { it != targetPubkey }
        return rebuild(original, remaining, original.status() ?: StatusTag.STATUS.LIVE, seats, original.openMic(), original.lockedMicSeats())
    }

    /**
     * Claim an empty [seat] for [targetPubkey]. Fails if the seat is
     * taken, locked, or (when [requireOpenMic]) the room is closed-mic
     * and the actor is not host/mod.
     */
    fun claimSeat(
        original: MeetingSpaceEvent,
        targetPubkey: HexKey,
        seat: Int,
        requireOpenMic: Boolean,
        isHostOrMod: Boolean,
        unlockIfLocked: Boolean = false,
    ): EventTemplate<MeetingSpaceEvent>? {
        if (seat !in 0 until MicSeatTag.SEAT_COUNT) return null
        if (requireOpenMic && !original.openMic() && !isHostOrMod) return null

        val locks = original.lockedMicSeats().toMutableSet()
        if (seat in locks) {
            if (!unlockIfLocked || !isHostOrMod) return null
            locks.remove(seat)
        }

        val seats = original.micSeats().toMutableMap()
        val occupant = seats[seat]
        if (occupant != null && occupant != targetPubkey) return null

        // Leave previous seat if moving.
        seats.entries.removeAll { it.value == targetPubkey }
        seats[seat] = targetPubkey

        val all = original.participants()
        val existing = all.firstOrNull { it.pubKey == targetPubkey }
        val role =
            when {
                existing?.role.equals(ROLE.HOST.code, true) -> ROLE.HOST
                existing?.role.equals(ROLE.MODERATOR.code, true) -> ROLE.MODERATOR
                else -> ROLE.SPEAKER
            }
        val mutated =
            if (existing != null) {
                if (role == ROLE.SPEAKER && existing.effectiveRole() == ROLE.PARTICIPANT) {
                    all.map {
                        if (it.pubKey == targetPubkey) it.copy(role = ROLE.SPEAKER.code) else it
                    }
                } else {
                    all
                }
            } else {
                all + ParticipantTag(targetPubkey, null, ROLE.SPEAKER.code, null)
            }
        return rebuild(original, mutated, original.status() ?: StatusTag.STATUS.LIVE, seats, original.openMic(), locks)
    }

    fun leaveSeat(
        original: MeetingSpaceEvent,
        targetPubkey: HexKey,
    ): EventTemplate<MeetingSpaceEvent>? {
        val seats = original.micSeats()
        val all = original.participants()
        val existing = all.firstOrNull { it.pubKey == targetPubkey }
        val hadSeat = seats.values.any { it == targetPubkey }
        if (!hadSeat && existing == null) {
            // Presence-only open-mic occupant: tag as listener so every
            // client drops them from the stage (see buildParticipantGrid).
            return rebuild(
                original,
                all + ParticipantTag(targetPubkey, null, ROLE.PARTICIPANT.code, null),
                original.status() ?: StatusTag.STATUS.LIVE,
                seats,
                original.openMic(),
                original.lockedMicSeats(),
            )
        }
        if (!hadSeat && existing != null) {
            if (existing.role.equals(ROLE.HOST.code, true)) return null
            if (existing.role.equals(ROLE.PARTICIPANT.code, true)) return null
            return setRole(original, targetPubkey, ROLE.PARTICIPANT)
        }
        val nextSeats = seats.filterValues { it != targetPubkey }
        val mutated =
            if (existing != null &&
                !existing.role.equals(ROLE.HOST.code, true) &&
                !existing.role.equals(ROLE.MODERATOR.code, true)
            ) {
                all.map {
                    if (it.pubKey == targetPubkey) it.copy(role = ROLE.PARTICIPANT.code) else it
                }
            } else {
                all
            }
        return rebuild(original, mutated, original.status() ?: StatusTag.STATUS.LIVE, nextSeats, original.openMic(), original.lockedMicSeats())
    }

    fun setOpenMic(
        original: MeetingSpaceEvent,
        open: Boolean,
    ): EventTemplate<MeetingSpaceEvent> =
        rebuild(
            original,
            original.participants().ifEmpty { listOf(ParticipantTag(original.pubKey, null, ROLE.HOST.code, null)) },
            original.status() ?: StatusTag.STATUS.LIVE,
            original.micSeats(),
            open,
            original.lockedMicSeats(),
        )

    fun setMicLocked(
        original: MeetingSpaceEvent,
        seat: Int,
        locked: Boolean,
    ): EventTemplate<MeetingSpaceEvent>? {
        if (seat !in 0 until MicSeatTag.SEAT_COUNT) return null
        // Only empty seats can be locked; clear lock if occupied.
        if (locked && original.micSeats().containsKey(seat)) return null
        val locks = original.lockedMicSeats().toMutableSet()
        if (locked) locks.add(seat) else locks.remove(seat)
        return rebuild(
            original,
            original.participants().ifEmpty { listOf(ParticipantTag(original.pubKey, null, ROLE.HOST.code, null)) },
            original.status() ?: StatusTag.STATUS.LIVE,
            original.micSeats(),
            original.openMic(),
            locks,
        )
    }

    private fun rebuild(
        original: MeetingSpaceEvent,
        participants: List<ParticipantTag>,
        status: StatusTag.STATUS,
        seats: Map<Int, HexKey> = original.micSeats(),
        openMic: Boolean = original.openMic(),
        lockedSeats: Set<Int> = original.lockedMicSeats(),
    ): EventTemplate<MeetingSpaceEvent> {
        val host = ParticipantTag(original.pubKey, null, ROLE.HOST.code, null)
        val others =
            participants
                .filterNot { it.pubKey == original.pubKey }
                .map { tag ->
                    if (tag.role.equals(ROLE.HOST.code, ignoreCase = true)) {
                        tag.copy(role = ROLE.MODERATOR.code)
                    } else {
                        tag
                    }
                }

        val nextCreatedAt = (original.createdAt + 1L).coerceAtLeast(TimeUtils.now())
        // Occupied seats cannot stay locked.
        val locks = lockedSeats.filter { it !in seats }.toSet()

        return MeetingSpaceEvent.build(
            room = original.room().orEmpty(),
            status = status,
            service = original.service().orEmpty(),
            host = host,
            dTag = original.dTag(),
            createdAt = nextCreatedAt,
        ) {
            original.endpoint()?.let { endpoint(it) }
            original.summary()?.takeIf { it.isNotBlank() }?.let { summary(it) }
            original.image()?.takeIf { it.isNotBlank() }?.let { image(it) }
            original.relays().takeIf { it.isNotEmpty() }?.let { relays(it) }
            if (others.isNotEmpty()) participants(others)
            openMic(openMic)
            micSeats(seats)
            micLocks(locks)
        }
    }
}
