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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.video

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedContentState
import com.vitorpamplona.amethyst.ui.feeds.LoadingReelsSkeleton
import com.vitorpamplona.amethyst.ui.feeds.RefresheableBox
import com.vitorpamplona.amethyst.ui.feeds.RenderFeedContentState
import com.vitorpamplona.amethyst.ui.feeds.SaveableFeedContentState
import com.vitorpamplona.amethyst.ui.feeds.ScrollStateKeys
import com.vitorpamplona.amethyst.ui.feeds.WatchLifecycleAndUpdateModel
import com.vitorpamplona.amethyst.ui.layouts.DisappearingScaffold
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.topbars.TopBarWithBackButton
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.ShortsFeedLoaded
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.datasource.VideoFilterAssemblerSubscription
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.DividerThickness

/**
 * Reels-style pager for feed video posts. Reached by tapping a video in the Home feed: the
 * tapped post opens full-screen and the user keeps swiping through the other video posts,
 * exactly like Reels. The list comes from the Home video feed, so it carries the same posts
 * (and the same follow-list filter) as the feed the tap came from — not the portrait-only
 * Reels stream.
 */
@Composable
fun PostReelsScreen(
    startId: String?,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    PostReelsScreen(
        videoFeedContentState = accountViewModel.feedStates.videoFeed,
        startId = startId,
        accountViewModel = accountViewModel,
        nav = nav,
    )
}

@Composable
fun PostReelsScreen(
    videoFeedContentState: FeedContentState,
    startId: String?,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    WatchLifecycleAndUpdateModel(videoFeedContentState)
    WatchAccountForVideoScreen(videoFeedContentState = videoFeedContentState, accountViewModel = accountViewModel)
    VideoFilterAssemblerSubscription(accountViewModel)

    DisappearingScaffold(
        isInvertedLayout = false,
        topBar = {
            Column {
                TopBarWithBackButton(caption = stringRes(R.string.post_reels), nav = nav)
                HorizontalDivider(thickness = DividerThickness)
            }
        },
        bottomBar = null,
        floatingButton = null,
        accountViewModel = accountViewModel,
    ) {
        PostReelsFeedBody(
            videoFeedContentState = videoFeedContentState,
            scrollKey = ScrollStateKeys.POST_REELS_SCREEN,
            startId = startId,
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }
}

@Composable
fun PostReelsFeedBody(
    videoFeedContentState: FeedContentState,
    scrollKey: String?,
    startId: String?,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    RefresheableBox(videoFeedContentState, enablePullRefresh = false) {
        SaveableFeedContentState(videoFeedContentState, scrollStateKey = scrollKey) { listState ->
            RenderFeedContentState(
                feedContentState = videoFeedContentState,
                accountViewModel = accountViewModel,
                listState = listState,
                nav = nav,
                routeForLastRead = "VideosFeed",
                onLoaded = { loaded ->
                    ShortsFeedLoaded(
                        loaded = loaded,
                        listState = listState,
                        accountViewModel = accountViewModel,
                        nav = nav,
                        isTabActive = true,
                        initialNoteId = startId,
                    )
                },
                onLoading = { LoadingReelsSkeleton() },
            )
        }
    }
}
