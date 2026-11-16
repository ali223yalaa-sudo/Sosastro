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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.ui.note.ClickableUserPicture
import com.vitorpamplona.amethyst.ui.note.UsernameDisplay
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.screen.NestRoomCard
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.screen.NestRoomPurple
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.tags.MicSeatTag

internal sealed class MicSeatTap {
    data class Empty(
        val seat: Int,
        val locked: Boolean,
    ) : MicSeatTap()

    data class Occupied(
        val seat: Int,
        val pubkey: HexKey,
        val isMe: Boolean,
    ) : MicSeatTap()
}

/** A gradient ring around a mic seat: the app's purple for a live speaker, a
 *  dimmed purple for an open seat, and a locked tint when the host closed it. */
private fun micSeatRing(
    occupied: Boolean,
    locked: Boolean,
): Brush =
    when {
        locked ->
            Brush.linearGradient(
                listOf(NestRoomPurple.copy(alpha = 0.9f), NestRoomPurple.copy(alpha = 0.4f)),
            )
        occupied ->
            Brush.linearGradient(
                listOf(NestRoomPurple, Color(0xFFB39DFF)),
            )
        else ->
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.1f)),
            )
    }

/**

 * Fixed 10-mic seat grid. Empty seats show a mic (or lock); occupied

 * seats show the speaker. Host taps open an action menu; guests take

 * empty unlocked seats when open-mic allows.

 */

@Composable
internal fun MicSeatsGrid(
    seatOccupants: Map<Int, HexKey>,
    lockedSeats: Set<Int>,
    mutedPubkeys: Set<HexKey>,
    myPubkey: HexKey,
    openMic: Boolean,
    canSpeak: Boolean,
    isHost: Boolean,
    isHostOrMod: Boolean,
    accountViewModel: AccountViewModel,
    onTakeSeat: (Int, Boolean) -> Unit,
    onLeaveSeat: () -> Unit,
    onLockSeat: (Int, locked: Boolean) -> Unit,
    onRemoveFromMic: (HexKey) -> Unit,
    onViewProfile: (HexKey) -> Unit,
    onTapSelf: (() -> Unit)?,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
) {
    val mySeat = seatOccupants.entries.firstOrNull { it.value == myPubkey }?.key

    var pendingTap by remember { mutableStateOf<MicSeatTap?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier =

                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = false,
        ) {
            items(MicSeatTag.SEAT_COUNT, key = { it }) { seat ->

                val occupant = seatOccupants[seat]

                val locked = seat in lockedSeats && occupant == null

                // Any guest may self-claim an empty, unlocked seat by tapping —
                // no host invite and no open-mic flag required, so a listener can
                // step up in someone else's room (including an empty one). The
                // host keeps control via seat locks and can still remove/mute.
                val canGuestTake =
                    !locked &&
                        occupant == null

                MicSeatCell(
                    seatIndex = seat,
                    occupant = occupant,
                    isMe = occupant == myPubkey,
                    isLocked = locked,
                    isMuted = occupant != null && occupant in mutedPubkeys,
                    canTake = canGuestTake || isHostOrMod,
                    accountViewModel = accountViewModel,
                    onClick = {
                        when {
                            occupant == null -> {
                                if (isHost) {
                                    pendingTap = MicSeatTap.Empty(seat, locked)
                                } else if (canGuestTake) {
                                    onTakeSeat(seat, false)
                                }
                            }

                            occupant == myPubkey -> {
                                pendingTap = MicSeatTap.Occupied(seat, occupant, isMe = true)
                            }

                            isHost -> {
                                pendingTap = MicSeatTap.Occupied(seat, occupant, isMe = false)
                            }

                            else -> onViewProfile(occupant)
                        }
                    },
                )
            }
        }

        bottomBar?.invoke()
    }

    when (val tap = pendingTap) {
        is MicSeatTap.Empty -> {
            EmptySeatActionsDialog(
                seat = tap.seat,
                locked = tap.locked,
                alreadyOnMic = mySeat != null,
                onDismiss = { pendingTap = null },
                onTake = {
                    pendingTap = null

                    onTakeSeat(tap.seat, tap.locked)
                },
                onToggleLock = {
                    pendingTap = null

                    onLockSeat(tap.seat, !tap.locked)
                },
            )
        }

        is MicSeatTap.Occupied -> {
            OccupiedSeatActionsDialog(
                isMe = tap.isMe,
                onDismiss = { pendingTap = null },
                onLeave = {
                    pendingTap = null

                    onLeaveSeat()
                },
                onMuteToggle = {
                    pendingTap = null

                    onTapSelf?.invoke()
                },
                showMute = tap.isMe && onTapSelf != null,
                onRemove = {
                    pendingTap = null

                    onRemoveFromMic(tap.pubkey)
                },
                onViewProfile = {
                    pendingTap = null

                    onViewProfile(tap.pubkey)
                },
            )
        }

        null -> Unit
    }
}

@Composable
private fun EmptySeatActionsDialog(
    seat: Int,
    locked: Boolean,
    alreadyOnMic: Boolean,
    onDismiss: () -> Unit,
    onTake: () -> Unit,
    onToggleLock: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes(R.string.nest_mic_seat_label) + " ${seat + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onTake, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringRes(
                            if (alreadyOnMic) {
                                R.string.nest_mic_move
                            } else {
                                R.string.nest_mic_take
                            },
                        ),
                    )
                }

                TextButton(onClick = onToggleLock, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringRes(
                            if (locked) R.string.nest_mic_unlock else R.string.nest_mic_lock,
                        ),
                    )
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.cancel))
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun OccupiedSeatActionsDialog(
    isMe: Boolean,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onMuteToggle: () -> Unit,
    showMute: Boolean,
    onRemove: () -> Unit,
    onViewProfile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes(R.string.nest_mic_seat_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isMe) {
                    TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                        Text(stringRes(R.string.nest_mic_leave))
                    }

                    if (showMute) {
                        TextButton(onClick = onMuteToggle, modifier = Modifier.fillMaxWidth()) {
                            Text(stringRes(R.string.nest_mic_mute))
                        }
                    }
                } else {
                    TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                        Text(stringRes(R.string.nest_mic_remove))
                    }

                    TextButton(onClick = onViewProfile, modifier = Modifier.fillMaxWidth()) {
                        Text(stringRes(R.string.nest_participant_view_profile))
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.cancel))
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MicSeatCell(
    seatIndex: Int,
    occupant: HexKey?,
    isMe: Boolean,
    isLocked: Boolean,
    isMuted: Boolean,
    canTake: Boolean,
    accountViewModel: AccountViewModel,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =

            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (occupant != null) 2.dp else 1.5.dp,
                        brush = micSeatRing(occupied = occupant != null, locked = isLocked),
                        shape = CircleShape,
                    ).background(
                        when {
                            isLocked -> NestRoomPurple.copy(alpha = 0.35f)
                            occupant != null -> NestRoomCard
                            else -> Color.White.copy(alpha = 0.06f)
                        },
                    ),
        ) {
            when {
                occupant != null -> {
                    Box(modifier = Modifier.alpha(if (isMuted) 0.45f else 1f)) {
                        ClickableUserPicture(
                            baseUserHex = occupant,
                            size = 58.dp,
                            accountViewModel = accountViewModel,
                            onClick = { onClick() },
                            onLongClick = { onClick() },
                        )
                    }

                    if (isMuted) {
                        Icon(
                            symbol = MaterialSymbols.MicOff,
                            contentDescription = stringRes(R.string.nest_mic_mute),
                            tint = Color.White,
                            modifier =

                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(20.dp)
                                    .background(Color.Black.copy(alpha = 0.72f), CircleShape)
                                    .padding(3.dp),
                        )
                    }
                }

                isLocked -> {
                    Icon(
                        symbol = MaterialSymbols.Lock,
                        contentDescription = stringRes(R.string.nest_mic_locked),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                else -> {
                    Icon(
                        symbol = MaterialSymbols.Mic,
                        contentDescription = stringRes(R.string.nest_mic_seat_empty, seatIndex + 1),
                        tint =

                            if (canTake) {
                                Color.White.copy(alpha = 0.9f)
                            } else {
                                Color.White.copy(alpha = 0.4f)
                            },
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }

        if (occupant != null) {
            val user = remember(occupant) { LocalCache.getOrCreateUser(occupant) }

            UsernameDisplay(
                baseUser = user,
                weight = Modifier.fillMaxWidth().alpha(if (isMuted) 0.5f else 1f),
                accountViewModel = accountViewModel,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text =
                    stringRes(
                        if (isLocked) R.string.nest_mic_locked else R.string.nest_mic_seat_label,
                    ),
                fontSize = 10.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
