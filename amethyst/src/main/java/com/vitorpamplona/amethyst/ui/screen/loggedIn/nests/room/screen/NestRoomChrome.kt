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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.viewmodels.RoomMember
import com.vitorpamplona.amethyst.model.AddressableNote
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.note.ClickableUserPicture
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes

internal val NestRoomPurple = Color(0xFF7C4DFF)
internal val NestRoomCard = Color(0xFF1A1A1A)

private val HeaderCardShape = RoundedCornerShape(22.dp)
private val HeaderImageShape = RoundedCornerShape(10.dp)

@Composable
internal fun UnknownPersonCover(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF2B2B2B)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            symbol = MaterialSymbols.Person,
            contentDescription = stringRes(R.string.nest_unknown_cover),
            tint = Color(0xFF9E9E9E),
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
internal fun NestRoomHeader(
    title: String,
    imageUrl: String?,
    isHost: Boolean,
    openMic: Boolean,
    showHostMenu: Boolean,
    showMinimize: Boolean,
    onMinimize: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onToggleOpenMic: () -> Unit,
    onLeave: () -> Unit,
    handRaised: Boolean,
    showHandRaise: Boolean,
    onToggleHand: () -> Unit,
    onReact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onLeave) {
                Icon(
                    symbol = MaterialSymbols.AutoMirrored.Logout,
                    contentDescription = stringRes(R.string.nest_leave),
                    tint = Color.White,
                )
            }
            Box {
                IconButton(onClick = onMenuOpen) {
                    Icon(
                        symbol = MaterialSymbols.MoreVert,
                        contentDescription = stringRes(R.string.nest_overflow_menu),
                        tint = Color.White,
                    )
                }
                DropdownMenu(
                    expanded = showHostMenu,
                    onDismissRequest = onMenuDismiss,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringRes(R.string.nest_share_action)) },
                        onClick = onShare,
                    )
                    if (showHandRaise) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringRes(
                                        if (handRaised) R.string.nest_lower_hand else R.string.nest_raise_hand,
                                    ),
                                )
                            },
                            onClick = {
                                onMenuDismiss()
                                onToggleHand()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringRes(R.string.nest_reactions_button)) },
                        onClick = onReact,
                    )
                    if (showMinimize) {
                        DropdownMenuItem(
                            text = { Text(stringRes(R.string.nest_minimize_description)) },
                            onClick = {
                                onMenuDismiss()
                                onMinimize()
                            },
                        )
                    }
                    if (isHost) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringRes(
                                        if (openMic) R.string.nest_close_mics else R.string.nest_open_mics,
                                    ),
                                )
                            },
                            onClick = onToggleOpenMic,
                        )
                        DropdownMenuItem(
                            text = { Text(stringRes(R.string.nest_edit_title)) },
                            onClick = onEdit,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier =
                    Modifier
                        .widthIn(max = 220.dp)
                        .clip(HeaderCardShape)
                        .background(NestRoomCard)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(HeaderImageShape)
                            .background(Color(0xFF2B2B2B)),
                ) {
                    if (!imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        UnknownPersonCover(Modifier.matchParentSize())
                    }
                }
            }
        }
    }
}

@Composable
internal fun NestAudiencePreviewRow(
    presentCount: Int,
    previewMembers: List<RoomMember>,
    accountViewModel: AccountViewModel,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenList)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(NestRoomPurple),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        symbol = MaterialSymbols.Person,
                        contentDescription = stringRes(R.string.nest_people_in_room),
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = presentCount.toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            previewMembers.take(5).forEach { member ->
                ClickableUserPicture(
                    baseUserHex = member.pubkey,
                    size = 36.dp,
                    accountViewModel = accountViewModel,
                    onClick = { _ -> onOpenList() },
                )
            }
        }
    }
}

@Composable
internal fun NestRoomFooter(
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    showMicMute: Boolean,
    isMicMuted: Boolean,
    onToggleMicMute: () -> Unit,
    roomNote: AddressableNote,
    accountViewModel: AccountViewModel,
    nav: INav,
    composer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NestZapButton(
                roomNote = roomNote,
                accountViewModel = accountViewModel,
                nav = nav,
            )
            Box(modifier = Modifier.weight(1f)) {
                composer()
            }
            if (showMicMute) {
                IconButton(onClick = onToggleMicMute) {
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(NestRoomPurple),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            symbol = if (isMicMuted) MaterialSymbols.MicOff else MaterialSymbols.Mic,
                            contentDescription =
                                stringRes(
                                    if (isMicMuted) R.string.nest_mic_unmute else R.string.nest_mic_mute,
                                ),
                            tint = Color.White,
                        )
                    }
                }
            }
            IconButton(onClick = onToggleMute) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(NestRoomPurple),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        symbol =
                            if (isMuted) {
                                MaterialSymbols.AutoMirrored.VolumeOff
                            } else {
                                MaterialSymbols.AutoMirrored.VolumeUp
                            },
                        contentDescription =
                            stringRes(
                                if (isMuted) R.string.nest_unmute_room else R.string.nest_mute_room,
                            ),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
