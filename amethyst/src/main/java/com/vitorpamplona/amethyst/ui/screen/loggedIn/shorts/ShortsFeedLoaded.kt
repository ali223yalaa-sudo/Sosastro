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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedState
import com.vitorpamplona.amethyst.service.tts.TextToSpeechHelper
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.isReelPlayableVideo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun ShortsFeedLoaded(
    loaded: FeedState.Loaded,
    listState: LazyListState,
    accountViewModel: AccountViewModel,
    nav: INav,
    isTabActive: Boolean = true,
    initialNoteId: String? = null,
) {
    val items by loaded.feed.collectAsStateWithLifecycle()
    val videoItems =
        remember(items) {
            items.list.filter { it.event?.isReelPlayableVideo() == true }
        }

    // Opened from a tapped feed video: land on that post instead of the top of the pager.
    // Runs once per (feed, target) pair so later scrolling by the user is never overridden.
    var scrolledToInitial by remember(items) { mutableStateOf(false) }
    LaunchedEffect(videoItems, initialNoteId) {
        if (scrolledToInitial || initialNoteId == null) return@LaunchedEffect
        val index = videoItems.indexOfFirst { it.idHex == initialNoteId }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
        scrolledToInitial = true
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
 * Snap fling for the reels pager. The stock [rememberSnapFlingBehavior] lets a hard flick
 * coast several items before it snaps, so one fast swipe can land two reels ahead —
 * disorienting on a full-screen feed, and the extra decode work on the skipped pages is what
 * makes the feed stutter. This drives the scroll itself: a flick advances exactly one reel
 * (from wherever the drag ended), a slow release settles on the nearest reel, and the coast is
 * a single bounded animation instead of a long free decay.
 */
@Composable
internal fun rememberReelsSnapFlingBehavior(listState: LazyListState): FlingBehavior {
    val viewConfiguration = LocalViewConfiguration.current
    return remember(listState, viewConfiguration) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val info = listState.layoutInfo
                val itemSize = info.visibleItemsInfo.firstOrNull()?.size ?: 0
                if (itemSize <= 0) return initialVelocity

                // Continuous page position: an integer when a reel is perfectly snapped.
                val position =
                    listState.firstVisibleItemIndex +
                        listState.firstVisibleItemScrollOffset.toFloat() / itemSize

                val minFling = viewConfiguration.minimumFlingVelocity.toFloat()
                val target =
                    when {
                        // Fast forward flick: exactly the next reel, never two.
                        initialVelocity > minFling -> floor(position).toInt() + 1
                        // Fast backward flick: exactly the previous reel.
                        initialVelocity < -minFling -> ceil(position).toInt() - 1
                        // Slow release: settle on whichever reel is closest.
                        else -> position.roundToInt()
                    }

                val clamped = target.coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))
                val delta = (clamped - position) * itemSize
                if (abs(delta) < 0.5f) return 0f

                var previous = 0f
                animate(0f, delta, animationSpec = spring<Float>(stiffness = Spring.StiffnessMedium)) { value, _ ->
                    scrollBy(value - previous)
                    previous = value
                }
                return 0f
            }
        }
    }
}
