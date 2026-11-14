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

import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ParticipantTag
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ROLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParticipantGridTest {
    private val host = "a".repeat(64)
    private val speaker = "b".repeat(64)
    private val listener = "c".repeat(64)

    private fun pTag(
        pk: String,
        role: ROLE,
    ) = ParticipantTag(pubKey = pk, relayHint = null, role = role.code, proof = null)

    private fun presence(
        pk: String,
        ts: Long,
        onstage: Boolean = true,
        handRaised: Boolean = false,
        publishing: Boolean = false,
        muted: Boolean? = null,
    ) = RoomPresence(
        pubkey = pk,
        handRaised = handRaised,
        muted = muted,
        publishing = publishing,
        onstage = onstage,
        updatedAtSec = ts,
    )

    @Test
    fun hostAndSpeakerLandOnStageWhenPresenceMatches() {
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(speaker, ROLE.SPEAKER)),
                presences = mapOf(host to presence(host, 100L), speaker to presence(speaker, 100L)),
            )
        assertEquals(2, grid.onStage.size)
        assertEquals(0, grid.audience.size)
        assertEquals(setOf(host, speaker), grid.onStage.map { it.pubkey }.toSet())
    }

    @Test
    fun speakerWithOnstageFalseDropsToAudience() {
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(speaker, ROLE.SPEAKER)),
                presences =
                    mapOf(
                        host to presence(host, 100L),
                        speaker to presence(speaker, 100L, onstage = false),
                    ),
            )
        assertEquals(setOf(host), grid.onStage.map { it.pubkey }.toSet())
        assertEquals(setOf(speaker), grid.audience.map { it.pubkey }.toSet())
    }

    @Test
    fun pureAudienceMembersAppearInAudience() {
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST)),
                presences = mapOf(host to presence(host, 100L), listener to presence(listener, 100L)),
            )
        // host on stage; the kind-10312-only listener in audience.
        assertEquals(setOf(host), grid.onStage.map { it.pubkey }.toSet())
        assertEquals(setOf(listener), grid.audience.map { it.pubkey }.toSet())
        // The audience member has no role.
        assertEquals(null, grid.audience.first().role)
    }

    @Test
    fun absentParticipantTagDoesNotMintAGhost() {
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(speaker, ROLE.SPEAKER)),
                presences = mapOf(host to presence(host, 100L)),
            )
        assertEquals(setOf(host), grid.onStage.map { it.pubkey }.toSet())
        assertTrue(grid.audience.none { it.pubkey == speaker })
        assertTrue(grid.onStage.none { it.pubkey == speaker })
    }

    @Test
    fun emptyInputProducesEmptyGrid() {
        val grid = buildParticipantGrid(emptyList(), emptyMap())
        assertEquals(0, grid.onStage.size)
        assertEquals(0, grid.audience.size)
    }

    @Test
    fun implicitHostWithoutPresenceDoesNotAppear() {
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(speaker, ROLE.SPEAKER)),
                presences = emptyMap(),
                hostPubkey = host,
            )
        assertTrue(grid.onStage.isEmpty())
        assertTrue(grid.audience.isEmpty())
    }

    @Test
    fun implicitHostWithOnstageTrueStaysOnStage() {
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(speaker, ROLE.SPEAKER)),
                presences = mapOf(host to presence(host, 100L, onstage = true)),
                hostPubkey = host,
            )
        val hostRow = grid.onStage.firstOrNull { it.pubkey == host }
        assertEquals(ROLE.HOST, hostRow?.role)
        assertEquals(false, hostRow?.absent)
    }

    @Test
    fun implicitHostLeavesStageDropsToAudienceKeepingHostRole() {
        // The host taps "Leave Stage" on nostrnests → kind-10312 with
        // onstage=0. They should drop to audience but the host crown
        // stays — exact same behaviour as an explicit speaker stepping
        // off the stage.
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(speaker, ROLE.SPEAKER)),
                presences = mapOf(host to presence(host, 100L, onstage = false)),
                hostPubkey = host,
            )
        assertTrue(grid.onStage.none { it.pubkey == host })
        val hostRow = grid.audience.firstOrNull { it.pubkey == host }
        assertEquals(ROLE.HOST, hostRow?.role)
    }

    @Test
    fun stalePresenceWithOnstageFalseIsIgnoredAfterRoleGrant() {
        // Audience-promoted-to-speaker scenario: presence event was
        // emitted at t=100 with onstage=false (their pre-promotion
        // audience-mode kind-10312). The host then promotes them at
        // t=200 (the room's new createdAt). Without the staleness
        // gate, this lands the speaker in the audience tab even
        // though their role just changed — the bug reported on
        // 2026-05-13. With roleGrantSec=200, the t=100 presence is
        // stale and the role tag wins → speaker on stage.
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(speaker, ROLE.SPEAKER)),
                presences =
                    mapOf(
                        host to presence(host, 200L),
                        speaker to presence(speaker, 100L, onstage = false),
                    ),
                roleGrantSec = 200L,
            )
        assertEquals(setOf(host, speaker), grid.onStage.map { it.pubkey }.toSet())
        assertEquals(0, grid.audience.size)
    }

    @Test
    fun freshOnstageFalseAfterRoleGrantStillDropsToAudience() {
        // Inverse: speaker emits onstage=0 AFTER the role grant
        // (deliberate "stepped off stage" while keeping the role).
        // Should still drop to audience — the existing leave-stage
        // contract.
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(speaker, ROLE.SPEAKER)),
                presences =
                    mapOf(
                        host to presence(host, 200L),
                        speaker to presence(speaker, 300L, onstage = false),
                    ),
                roleGrantSec = 200L,
            )
        assertEquals(setOf(host), grid.onStage.map { it.pubkey }.toSet())
        assertEquals(setOf(speaker), grid.audience.map { it.pubkey }.toSet())
    }

    @Test
    fun explicitHostInPTagsIsNotDuplicated() {
        // The author already tagged themselves with role=host. The
        // synthesized entry must NOT shadow / duplicate them.
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(speaker, ROLE.SPEAKER)),
                presences = mapOf(host to presence(host, 100L), speaker to presence(speaker, 100L)),
                hostPubkey = host,
            )
        assertEquals(2, grid.onStage.size)
        assertEquals(1, grid.onStage.count { it.pubkey == host })
    }

    @Test
    fun openMicGuestWithStaleParticipantRoleStillAscendsOnSeatClaim() {
        // Regression: a guest who once stepped off a mic is stamped
        // PARTICIPANT by leaveSeat. When they later self-claim an open
        // seat their role is still PARTICIPANT, but the live micSeat
        // claim must win so everyone subscribes to their audio.
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(listener, ROLE.PARTICIPANT)),
                presences =
                    mapOf(
                        host to presence(host, 100L),
                        listener to presence(listener, 200L).copy(micSeat = 3),
                    ),
                hostPubkey = host,
                roleGrantSec = 50L,
                openMic = true,
            )
        assertTrue(grid.onStage.any { it.pubkey == listener })
        assertEquals(0, grid.audience.count { it.pubkey == listener })
    }

    @Test
    fun openMicGuestWithoutSeatClaimStaysInAudience() {
        // Guardrail for the fix above: merely being present in an
        // open-mic room must NOT put an audience member on stage — the
        // ascent is still driven by an explicit seat claim.
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(listener, ROLE.PARTICIPANT)),
                presences =
                    mapOf(
                        host to presence(host, 100L),
                        listener to presence(listener, 200L),
                    ),
                hostPubkey = host,
                roleGrantSec = 50L,
                openMic = true,
            )
        assertTrue(grid.onStage.none { it.pubkey == listener })
        assertTrue(grid.audience.any { it.pubkey == listener })
    }

    @Test
    fun closedMicGuestWithSeatClaimAscends() {
        // A guest may step up in someone else's room by self-claiming an
        // empty seat, even when the host never opened the mic. The ascent
        // is driven by the explicit seat claim, so the room must put them
        // on stage regardless of the open-mic flag.
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(listener, ROLE.PARTICIPANT)),
                presences =
                    mapOf(
                        host to presence(host, 100L),
                        listener to presence(listener, 200L).copy(micSeat = 4),
                    ),
                hostPubkey = host,
                roleGrantSec = 50L,
                openMic = false,
            )
        assertTrue(grid.onStage.any { it.pubkey == listener })
        assertEquals(0, grid.audience.count { it.pubkey == listener })
    }

    @Test
    fun openMicGuestWithSeatClaimOlderThanRoleGrantStillAscends() {
        // Regression: the host republishes the room every time a seat
        // changes, which bumps `created_at` past a guest's last 15-s
        // heartbeat. The old freshness gate (`updatedAtSec >=
        // roleGrantSec`) then treated the guest's live seat claim as
        // stale and dropped them off-stage until their next heartbeat —
        // the "guest flashes in and out of the stage" report. A live
        // self-claim must survive a role grant newer than the heartbeat.
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(listener, ROLE.PARTICIPANT)),
                presences =
                    mapOf(
                        host to presence(host, 500L),
                        // Guest claimed seat 3 at t=100; host republished
                        // at t=400 (roleGrantSec) — presence now "stale".
                        listener to presence(listener, 100L).copy(micSeat = 3),
                    ),
                hostPubkey = host,
                roleGrantSec = 400L,
                openMic = true,
            )
        assertTrue(grid.onStage.any { it.pubkey == listener })
        assertEquals(0, grid.audience.count { it.pubkey == listener })
    }

    @Test
    fun stalePresenceWithoutSeatClaimStillYieldsToRoleGrant() {
        // Guardrail: the freshness bypass is scoped to live self-claims.
        // A guest with NO seat claim whose presence predates the role
        // grant must still fall back to the role tag (the promoted-
        // audience-member case the gate was added for).
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(speaker, ROLE.SPEAKER)),
                presences =
                    mapOf(
                        host to presence(host, 500L),
                        speaker to presence(speaker, 100L, onstage = false),
                    ),
                roleGrantSec = 400L,
            )
        assertEquals(setOf(host, speaker), grid.onStage.map { it.pubkey }.toSet())
    }

    @Test
    fun leftPresenceIsDroppedFromOccupancy() {
        val grid =
            buildParticipantGrid(
                participants = listOf(pTag(host, ROLE.HOST), pTag(speaker, ROLE.SPEAKER)),
                presences =
                    mapOf(
                        host to presence(host, 100L),
                        speaker to presence(speaker, 200L).copy(left = true),
                    ),
                hostPubkey = host,
            )
        assertEquals(setOf(host), (grid.onStage + grid.audience).map { it.pubkey }.toSet())
    }
}
