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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.vitorpamplona.amethyst.commons.viewmodels.RoomPresence
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.participants.RoomParticipantActions
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import kotlinx.coroutines.delay

/**
 * Host-only authority bridge: presence claims ↔ kind-30312 mic tags.
 *
 * Guests cannot sign the replaceable room event, so their open-mic /
 * approved ascent only becomes visible to *everyone* (and removable by
 * the host) after the host republishes `mic` tags. Also drops seats when
 * a peer publishes onstage=0 (left the room / left the mic).
 */
@Composable
internal fun HostMicSeatAuthority(
    enabled: Boolean,
    event: MeetingSpaceEvent,
    hostPubkey: String,
    openMic: Boolean,
    lockedSeats: Set<Int>,
    presences: Map<String, RoomPresence>,
    accountViewModel: AccountViewModel,
) {
    if (!enabled) return

    val eventRef by rememberUpdatedState(event)
    val openMicRef by rememberUpdatedState(openMic)
    val lockedRef by rememberUpdatedState(lockedSeats)
    val presenceRef by rememberUpdatedState(presences)

    LaunchedEffect(presences, event.micSeats(), event.participants(), openMic, lockedSeats) {
        // Debounce rapid presence heartbeats so we don't thrash relays.
        delay(350)
        val current = eventRef
        val speakers =
            current
                .participants()
                .filter { it.canSpeak() }
                .map { it.pubKey }
                .toSet()
        val hostSeats = current.micSeats()
        val locked = lockedRef

        // 1) Promote presence claims → authoritative mic tags.
        for (p in presenceRef.values) {
            if (!p.isOccupying()) continue
            val seat = p.micSeat ?: continue
            if (!p.onstage) continue
            if (seat in locked) continue
            val occupant = hostSeats[seat]
            if (occupant == p.pubkey) continue
            if (occupant != null) continue
            val allowed = openMicRef || p.pubkey in speakers || p.pubkey == hostPubkey
            if (!allowed) continue
            RoomParticipantActions
                .claimSeat(
                    original = current,
                    targetPubkey = p.pubkey,
                    seat = seat,
                    requireOpenMic = false,
                    isHostOrMod = true,
                    unlockIfLocked = false,
                )?.let { template ->
                    accountViewModel.launchSigner {
                        accountViewModel.account.signAndComputeBroadcast(template)
                    }
                    return@LaunchedEffect
                }
        }

        // 2) Peer left or stepped off → remove from host mic tags (including host).
        for ((_, pk) in hostSeats) {
            val p = presenceRef[pk]
            if (p != null && p.isOccupying() && p.onstage) continue
            RoomParticipantActions.leaveSeat(current, pk)?.let { template ->
                accountViewModel.launchSigner {
                    accountViewModel.account.signAndComputeBroadcast(template)
                }
                return@LaunchedEffect
            }
        }
    }
}
