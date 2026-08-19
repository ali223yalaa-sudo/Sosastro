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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.user.observeUserInfo
import com.vitorpamplona.amethyst.ui.components.RobohashFallbackAsyncImage
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.tags.StatusTag

internal val NestGoldBorder = Color(0xFFD7B45A)
internal val NestRibbonBlue = Color(0xFF3B6FE8)
internal val NestSignalTeal = Color(0xFF2EE0C5)
internal val NestLobbyCard = Color(0xFF171717)
internal val NestTabTrack = Color(0xFF121212)
internal val NestGridBorder = Color(0xFF3A4450)

private val HeroCorner = RoundedCornerShape(18.dp)
private val AvatarCorner = RoundedCornerShape(16.dp)
private val PillCorner = RoundedCornerShape(24.dp)
private val ActiveTabBrush =
    Brush.verticalGradient(
        colors = listOf(Color(0xFF4A4A4A), Color(0xFF2C2C2C)),
    )

@Composable
fun NestsJoinedDiscoverTabs(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(48.dp)
                .clip(PillCorner)
                .background(NestTabTrack),
    ) {
        NestsPillTab(
            label = stringRes(R.string.nests_tab_joined),
            selected = selectedTab == 0,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(0) },
        )
        NestsPillTab(
            label = stringRes(R.string.nests_tab_discover),
            selected = selectedTab == 1,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(1) },
        )
    }
}

@Composable
private fun NestsPillTab(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .padding(3.dp)
                .clip(RoundedCornerShape(21.dp))
                .then(
                    if (selected) {
                        Modifier.background(ActiveTabBrush)
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFFBDBDBD),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun NestsMyRoomHero(
    ownedNest: MeetingSpaceEvent?,
    accountViewModel: AccountViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val me = accountViewModel.userProfile()
    val userState by observeUserInfo(me, accountViewModel)
    val cover = ownedNest?.image()?.ifBlank { null }
    val title =
        ownedNest?.room()?.ifBlank { null }
            ?: userState?.info?.bestName()
            ?: me.pubkeyDisplayHex()
    val subtitle =
        ownedNest?.summary()?.ifBlank { null }
            ?: userState?.info?.about
            ?: ""
    val live = ownedNest?.checkStatus(ownedNest.status()) == StatusTag.STATUS.LIVE
    val occupancyNote =
        remember(me.pubkeyHex, ownedNest?.id) {
            val address =
                ownedNest?.address()
                    ?: com.vitorpamplona.quartz.nip01Core.core.Address(
                        MeetingSpaceEvent.KIND,
                        me.pubkeyHex,
                        MeetingSpaceEvent.PERMANENT_ROOM_D_TAG,
                    )
            com.vitorpamplona.amethyst.model.LocalCache
                .getOrCreateAddressableNote(address)
        }
    val listeners by observeRoomOccupancyCount(occupancyNote, accountViewModel)
    val autoPlay by accountViewModel.settings.autoPlayVideosFlow.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp)
                    .background(NestLobbyCard, HeroCorner)
                    .border(1.5.dp, NestGoldBorder, HeroCorner)
                    .clickable(onClick = onClick)
                    .height(124.dp),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                RoomSignalBadge(
                    count = listeners,
                    live = live,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = 10.dp),
                )
                MyRoomRibbon(modifier = Modifier.align(Alignment.TopEnd))
            }
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(start = 16.dp, end = 16.dp, top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = Color(0xFFB0B0B0),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .size(80.dp)
                    .clip(AvatarCorner)
                    .border(2.dp, Color(0xFFC8C8C8), AvatarCorner)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onClick),
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                RobohashFallbackAsyncImage(
                    robot = me.pubkeyHex,
                    model = userState?.info?.picture,
                    contentDescription = title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    loadProfilePicture = accountViewModel.settings.showProfilePictures(),
                    loadRobohash = accountViewModel.settings.isNotPerformanceMode(),
                    autoPlayGif = autoPlay,
                )
            }
        }
    }
}

@Composable
private fun MyRoomRibbon(modifier: Modifier) {
    Box(
        modifier =
            modifier
                .offset(x = 22.dp, y = 18.dp)
                .graphicsLayer { rotationZ = 40f }
                .background(NestRibbonBlue)
                .padding(horizontal = 28.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringRes(R.string.nest_my_room),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun RoomSignalBadge(
    count: Int,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = count.toString(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(5.dp))
        SignalBars(active = live)
    }
}

@Composable
private fun SignalBars(active: Boolean) {
    val on = if (active) NestSignalTeal else Color(0xFF6A6A6A)
    Canvas(modifier = Modifier.size(width = 14.dp, height = 12.dp)) {
        val barWidth = size.width / 6f
        val gap = barWidth * 0.45f
        val heights = floatArrayOf(0.35f, 0.55f, 0.75f, 1f)
        heights.forEachIndexed { i, h ->
            val barH = size.height * h
            drawRoundRect(
                color = on,
                topLeft = Offset(i * (barWidth + gap), size.height - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
