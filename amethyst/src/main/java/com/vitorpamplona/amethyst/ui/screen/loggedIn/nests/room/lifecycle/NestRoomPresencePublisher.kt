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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.vitorpamplona.amethyst.commons.viewmodels.BroadcastUiState
import com.vitorpamplona.amethyst.commons.viewmodels.NestUiState
import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.presence.MeetingRoomPresenceEvent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Publish-side of the kind-10312 presence loop. Three things wired
 * together as one composable:
 *
 *   - **Heartbeat** — emit on enter, on hand-raise toggle, on
 *     onstage transition, then every [PRESENCE_REFRESH_MS]. Mute
 *     and publishing flags are intentionally NOT keys: every mute
 *     toggle would otherwise round-trip a presence emit (audit
 *     Android #11). The heartbeat reads them via
 *     [rememberUpdatedState] so each refresh picks up the latest
 *     value WITHOUT cancelling and restarting the loop — without
 *     that wrapper the captured-at-launch values stay frozen and
 *     a 30 s refresh would overwrite a fresh mute toggle with the
 *     pre-toggle state, hiding the avatar's mute icon.
 *
 *   - **Debounce** — after a mute toggle, wait
 *     [PRESENCE_DEBOUNCE_MS] for further changes before sending a
 *     fresh presence. The LaunchedEffect's auto-cancel on key
 *     change is the debounce mechanism. Skipped while we're not
 *     broadcasting (`micMutedTag == null`); otherwise the FIRST
 *     composition would publish twice within 500 ms (heartbeat
 *     immediately + debounce-publisher 500 ms later, both with
 *     muted=null) — audit round-2 Android #10.
 *
 *   - **Final leave** — onDispose runs a non-cancellable
 *     publishPresence(publishing=false, onstage=false) so the
 *     "I'm gone" event survives the composable's scope being
 *     cancelled mid-network. Without this the leave event almost
 *     never reaches the relay (audit Android #12).
 */
@Composable
internal fun NestPresencePublisher(
    account: Account,
    event: MeetingSpaceEvent,
    ui: NestUiState,
    handRaised: Boolean,
) {
    val micMutedTag: Boolean? =
        when (val b = ui.broadcast) {
            is BroadcastUiState.Broadcasting -> b.isMuted
            else -> null
        }
    val publishingTag: Boolean = ui.publishingNow
    val onstageTag: Boolean = ui.onStageNow
    val micSeatTag: Int? = ui.micSeat

    // Latest snapshots for the heartbeat. Without these, the
    // LaunchedEffect captures the values from FIRST composition; the
    // 30 s refresh would then overwrite a recent mute toggle with the
    // pre-toggle state, which presented as "the mute icon disappeared
    // a few seconds after I muted, but the mic is still hot."
    val currentHandRaised by rememberUpdatedState(handRaised)
    val currentMicMuted by rememberUpdatedState(micMutedTag)
    val currentPublishing by rememberUpdatedState(publishingTag)
    val currentOnstage by rememberUpdatedState(onstageTag)
    val currentMicSeat by rememberUpdatedState(micSeatTag)

    LaunchedEffect(event.address().toValue(), handRaised, onstageTag, micSeatTag) {
        publishPresence(account, event, currentHandRaised, currentMicMuted, currentPublishing, currentOnstage, currentMicSeat)
        while (isActive) {
            delay(PRESENCE_REFRESH_MS)
            publishPresence(account, event, currentHandRaised, currentMicMuted, currentPublishing, currentOnstage, currentMicSeat)
        }
    }

    if (micMutedTag != null) {
        LaunchedEffect(micMutedTag) {
            delay(PRESENCE_DEBOUNCE_MS)
            publishPresence(account, event, handRaised, micMutedTag, publishingTag, onstageTag, micSeatTag)
        }
    }

    DisposableEffect(event.address().toValue()) {
        onDispose {
            // Publish a terminal "I'm gone" presence on leave. Retransmit a
            // few times: a single fire-and-forget publish is easily lost to a
            // relay hiccup or a QUIC teardown racing the Activity finish, and
            // a lost leave is exactly what leaves a ghost avatar behind.
            // Receivers dedup by pubkey, so replays are harmless.
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                repeat(LEAVE_RETRANSMIT_COUNT) {
                    runCatching {
                        publishPresence(
                            account = account,
                            event = event,
                            handRaised = false,
                            micMuted = null,
                            publishing = false,
                            onstage = false,
                            micSeat = null,
                            left = true,
                        )
                    }
                    if (it < LEAVE_RETRANSMIT_COUNT - 1) delay(LEAVE_RETRANSMIT_DELAY_MS)
                }
            }
        }
    }
}

private suspend fun publishPresence(
    account: Account,
    event: MeetingSpaceEvent,
    handRaised: Boolean,
    micMuted: Boolean?,
    publishing: Boolean? = null,
    onstage: Boolean? = null,
    micSeat: Int? = null,
    left: Boolean? = null,
) {
    runCatching {
        account.signAndComputeBroadcast(
            MeetingRoomPresenceEvent.build(
                root = event,
                handRaised = handRaised,
                muted = micMuted,
                publishing = publishing,
                onstage = onstage,
                micSeat = micSeat,
                left = left,
            ),
        )
    }
}

private const val PRESENCE_REFRESH_MS = 15_000L
private const val PRESENCE_DEBOUNCE_MS = 250L

// Terminal leave is retransmitted so a dropped/raced publish can't strand a
// ghost avatar. Kept small so the leave lands well within the 2-min eviction
// fallback window even on a flaky link.
private const val LEAVE_RETRANSMIT_COUNT = 4
private const val LEAVE_RETRANSMIT_DELAY_MS = 700L
