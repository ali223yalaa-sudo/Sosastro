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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.viewmodels.RoomNotice
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.ui.note.ClickableUserPicture
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import kotlinx.coroutines.delay

private val JoinBannerEnter = Color(0xFF2EE0C5)
private const val JOIN_BANNER_VISIBLE_MS = 3_500L

/**
 * Transient "X joined the room" banner shown directly under the mic
 * grid. Driven by [com.vitorpamplona.amethyst.commons.viewmodels.NestViewModel.roomNotices]:
 * every fresh ENTERED notice flips the banner visible, and it fades
 * back out on its own so a busy room doesn't stack a permanent wall of
 * join lines. Enter-only — exits are handled by the presence list.
 */
@Composable
internal fun JoinBanner(
    notices: List<RoomNotice>,
    accountViewModel: AccountViewModel,
    modifier: Modifier = Modifier,
) {
    val latestEntered = remember(notices) { notices.lastOrNull { it.kind == RoomNotice.Kind.ENTERED } }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(latestEntered) {
        if (latestEntered == null) {
            visible = false
            return@LaunchedEffect
        }
        visible = true
        delay(JOIN_BANNER_VISIBLE_MS)
        visible = false
    }

    AnimatedVisibility(
        visible = visible && latestEntered != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val notice = latestEntered
        if (notice != null) {
            val user = remember(notice.pubkey) { LocalCache.getOrCreateUser(notice.pubkey) }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(JoinBannerEnter.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                ClickableUserPicture(
                    baseUser = user,
                    size = 22.dp,
                    accountViewModel = accountViewModel,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text =
                        stringRes(
                            R.string.nest_join_banner,
                            LocalCache.getUserIfExists(notice.pubkey)?.toBestDisplayName()
                                ?: notice.pubkey.take(8),
                        ),
                    color = JoinBannerEnter,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
