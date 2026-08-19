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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedContentState
import com.vitorpamplona.amethyst.service.playback.composable.DEFAULT_MUTED_SETTING
import com.vitorpamplona.amethyst.ui.feeds.PagerStateKeys
import com.vitorpamplona.amethyst.ui.feeds.RefresheableBox
import com.vitorpamplona.amethyst.ui.feeds.RenderFeedContentState
import com.vitorpamplona.amethyst.ui.feeds.SaveableFeedContentState
import com.vitorpamplona.amethyst.ui.feeds.ScrollStateKeys
import com.vitorpamplona.amethyst.ui.feeds.WatchLifecycleAndUpdateModel
import com.vitorpamplona.amethyst.ui.feeds.rememberForeverPagerState
import com.vitorpamplona.amethyst.ui.layouts.DisappearingScaffold
import com.vitorpamplona.amethyst.ui.navigation.bottombars.AppBottomBar
import com.vitorpamplona.amethyst.ui.navigation.bottombars.FabBottomBarPadded
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.longs.LongsFeedBody
import com.vitorpamplona.amethyst.ui.screen.loggedIn.longs.LongsTopBar
import com.vitorpamplona.amethyst.ui.screen.loggedIn.longs.NewLongVideoButton
import com.vitorpamplona.amethyst.ui.screen.loggedIn.longs.WatchAccountForLongsScreen
import com.vitorpamplona.amethyst.ui.screen.loggedIn.longs.datasource.LongsFilterAssemblerSubscription
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.ShortsFilterAssemblerSubscription
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.TabRowHeight
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

private enum class ShortsTabContent {
    Reels,
    Videos,
}

@Immutable
private class ShortsTabItem(
    val resource: Int,
    val feedState: FeedContentState,
    val scrollStateKey: String,
    val content: ShortsTabContent,
)

@Composable
fun ShortsScreen(
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    ShortsScreen(
        shortsFeedContentState = accountViewModel.feedStates.shortsFeed,
        longsFeedContentState = accountViewModel.feedStates.longsFeed,
        accountViewModel = accountViewModel,
        nav = nav,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(
    shortsFeedContentState: FeedContentState,
    longsFeedContentState: FeedContentState,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    WatchLifecycleAndUpdateModel(shortsFeedContentState)
    WatchLifecycleAndUpdateModel(longsFeedContentState)
    WatchAccountForShortsScreen(videoFeedState = shortsFeedContentState, accountViewModel = accountViewModel)
    WatchAccountForLongsScreen(videoFeedState = longsFeedContentState, accountViewModel = accountViewModel)
    ShortsFilterAssemblerSubscription(accountViewModel)
    LongsFilterAssemblerSubscription(accountViewModel)

    val tabs =
        remember(shortsFeedContentState, longsFeedContentState) {
            persistentListOf(
                ShortsTabItem(
                    resource = R.string.shorts,
                    feedState = shortsFeedContentState,
                    scrollStateKey = ScrollStateKeys.SHORTS_SCREEN,
                    content = ShortsTabContent.Reels,
                ),
                ShortsTabItem(
                    resource = R.string.longs,
                    feedState = longsFeedContentState,
                    scrollStateKey = ScrollStateKeys.LONGS_SCREEN,
                    content = ShortsTabContent.Videos,
                ),
            )
        }

    val pagerState = rememberForeverPagerState(key = PagerStateKeys.SHORTS_SCREEN) { tabs.size }

    ShortsPages(pagerState, tabs, accountViewModel, nav)
}

@Composable
private fun ShortsPages(
    pagerState: PagerState,
    tabs: ImmutableList<ShortsTabItem>,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val selectedTabIndex = pagerState.currentPage.coerceIn(0, tabs.lastIndex)
    val selectedTab = tabs[selectedTabIndex]
    val isReelsTab = selectedTab.content == ShortsTabContent.Reels

    // Mute default only while viewing the vertical reels feed.
    DisposableEffect(isReelsTab) {
        if (isReelsTab) {
            DEFAULT_MUTED_SETTING.value = true
        }
        onDispose {
            DEFAULT_MUTED_SETTING.value = true
        }
    }

    DisappearingScaffold(
        isInvertedLayout = false,
        topBar = {
            Column {
                if (isReelsTab) {
                    ShortsTopBar(accountViewModel, nav)
                } else {
                    LongsTopBar(accountViewModel, nav)
                }
                SecondaryTabRow(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    modifier = TabRowHeight,
                    selectedTabIndex = selectedTabIndex,
                ) {
                    val coroutineScope = rememberCoroutineScope()
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTabIndex == index,
                            text = { Text(text = stringRes(tab.resource)) },
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        )
                    }
                }
            }
        },
        bottomBar = {
            AppBottomBar(Route.Shorts, nav, accountViewModel) { route ->
                if (route == Route.Shorts) {
                    tabs.getOrNull(pagerState.currentPage)?.feedState?.sendToTop()
                } else {
                    nav.navBottomBar(route)
                }
            }
        },
        floatingButton =
            if (isReelsTab) {
                null
            } else {
                {
                    FabBottomBarPadded(nav) {
                        NewLongVideoButton(accountViewModel, nav, selectedTab.feedState::sendToTop)
                    }
                }
            },
        accountViewModel = accountViewModel,
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = true,
            modifier = Modifier,
        ) { page ->
            val tab = tabs[page]
            when (tab.content) {
                ShortsTabContent.Reels -> {
                    ShortsFeedBody(
                        shortsFeedContentState = tab.feedState,
                        scrollKey = tab.scrollStateKey,
                        accountViewModel = accountViewModel,
                        nav = nav,
                        isTabActive = selectedTabIndex == page,
                    )
                }
                ShortsTabContent.Videos -> {
                    LongsFeedBody(
                        longsFeedContentState = tab.feedState,
                        scrollKey = tab.scrollStateKey,
                        accountViewModel = accountViewModel,
                        nav = nav,
                    )
                }
            }
        }
    }
}

@Composable
fun ShortsFeedBody(
    shortsFeedContentState: FeedContentState,
    scrollKey: String?,
    accountViewModel: AccountViewModel,
    nav: INav,
    isTabActive: Boolean = true,
) {
    RefresheableBox(shortsFeedContentState, enablePullRefresh = false) {
        SaveableFeedContentState(shortsFeedContentState, scrollStateKey = scrollKey) { listState ->
            RenderFeedContentState(
                feedContentState = shortsFeedContentState,
                accountViewModel = accountViewModel,
                listState = listState,
                nav = nav,
                routeForLastRead = "ShortsFeed",
                onLoaded = { loaded ->
                    ShortsFeedLoaded(
                        loaded = loaded,
                        listState = listState,
                        accountViewModel = accountViewModel,
                        nav = nav,
                        isTabActive = isTabActive,
                    )
                },
            )
        }
    }
}

@Composable
fun WatchAccountForShortsScreen(
    videoFeedState: FeedContentState,
    accountViewModel: AccountViewModel,
) {
    val listState by accountViewModel.account.liveShortsFollowLists.collectAsStateWithLifecycle()
    val hiddenUsers =
        accountViewModel.account.hiddenUsers.flow
            .collectAsStateWithLifecycle()

    LaunchedEffect(accountViewModel, listState, hiddenUsers) {
        videoFeedState.checkKeysInvalidateDataAndSendToTop()
    }
}
