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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedState
import com.vitorpamplona.amethyst.service.tts.TextToSpeechHelper
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.isReelPlayableVideo
import kotlin.math.abs

@Composable
fun ShortsFeedLoaded(
    loaded: FeedState.Loaded,
    listState: LazyListState,
    accountViewModel: AccountViewModel,
    nav: INav,
    isTabActive: Boolean = true,
) {
    val items by loaded.feed.collectAsStateWithLifecycle()
    val videoItems =
        remember(items) {
            items.list.filter { it.event?.isReelPlayableVideo() == true }
        }
    val flingBehavior = rememberReelsSnapFlingBehavior(listState)
    val focusedIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }?.index
                ?: listState.firstVisibleItemIndex
        }
    }

    LaunchedEffect(focusedIndex) {
        TextToSpeechHelper.stopSpeaking()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        flingBehavior = flingBehavior,
        userScrollEnabled = isTabActive,
    ) {
        itemsIndexed(
            videoItems,
            key = { _, item -> item.idHex },
            contentType = { _, _ -> "short_reel" },
        ) { index, item ->
            VideoCardCompose(
                baseNote = item,
                accountViewModel = accountViewModel,
                nav = nav,
                reelsLayout = true,
                isActivePage = isTabActive && index == focusedIndex,
                modifier = Modifier.fillParentMaxSize(),
            )
        }
    }
}

/**
 * Snap fling for the reels pager. The stock [rememberSnapFlingBehavior] lets a hard flick coast
 * several items before it snaps, so one fast swipe can land two reels ahead — disorienting on a
 * full-screen feed. This clamps the coast to a single item: a flick advances exactly one reel,
 * however hard it is thrown, while a slow drag still settles on whichever reel is closest.
 */
@Composable
private fun rememberReelsSnapFlingBehavior(listState: LazyListState): FlingBehavior {
    val provider =
        remember(listState) {
            object : SnapLayoutInfoProvider {
                private val base = SnapLayoutInfoProvider(listState)

                override fun calculateApproachOffset(
                    velocity: Float,
                    decayOffset: Float,
                ): Float {
                    val itemSize =
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull()
                            ?.size ?: 0
                    if (itemSize == 0) return base.calculateApproachOffset(velocity, decayOffset)
                    // Never coast past one full item, so a hard flick cannot skip the next reel.
                    return decayOffset.coerceIn(-itemSize.toFloat(), itemSize.toFloat())
                }

                override fun calculateSnapOffset(velocity: Float): Float = base.calculateSnapOffset(velocity)
            }
        }
    return rememberSnapFlingBehavior(snapLayoutInfoProvider = provider)
}
