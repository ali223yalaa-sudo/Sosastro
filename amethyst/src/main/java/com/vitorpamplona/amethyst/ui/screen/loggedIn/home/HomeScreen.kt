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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.Absolute.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.model.emphChat.EphemeralChatChannel
import com.vitorpamplona.amethyst.commons.model.nip53LiveActivities.LiveActivitiesChannel
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedContentState
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedState
import com.vitorpamplona.amethyst.commons.ui.layouts.rememberFeedContentPadding
import com.vitorpamplona.amethyst.model.TopFilter
import com.vitorpamplona.amethyst.service.OnlineChecker
import com.vitorpamplona.amethyst.service.tts.TextToSpeechHelper
import com.vitorpamplona.amethyst.ui.actions.CrossfadeIfEnabled
import com.vitorpamplona.amethyst.ui.dal.usesAstroInterestOrder
import com.vitorpamplona.amethyst.ui.feeds.ChannelFeedContentState
import com.vitorpamplona.amethyst.ui.feeds.ChannelFeedState
import com.vitorpamplona.amethyst.ui.feeds.LocalFeedListIsScrolling
import com.vitorpamplona.amethyst.ui.feeds.RefresheableBox
import com.vitorpamplona.amethyst.ui.feeds.RenderFeedContentState
import com.vitorpamplona.amethyst.ui.feeds.SaveableFeedContentState
import com.vitorpamplona.amethyst.ui.feeds.ScrollStateKeys
import com.vitorpamplona.amethyst.ui.feeds.WatchAstroScrollLoad
import com.vitorpamplona.amethyst.ui.feeds.WatchLifecycleAndUpdateModel
import com.vitorpamplona.amethyst.ui.layouts.DisappearingScaffold
import com.vitorpamplona.amethyst.ui.layouts.WatchRevealChromeAtFeedTop
import com.vitorpamplona.amethyst.ui.navigation.bottombars.AppBottomBar
import com.vitorpamplona.amethyst.ui.navigation.bottombars.FabBottomBarPadded
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.note.NoteCompose
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.geohash.NewGeoPostButton
import com.vitorpamplona.amethyst.ui.screen.loggedIn.hashtag.NewHashtagPostButton
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.datasource.HomeFilterAssemblerSubscription
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.live.RenderEphemeralBubble
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.live.RenderLiveActivityBubble
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.stories.HomeEphemeralStoryRings
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.NewImageButton
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.StoriesTopBar
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.VideoFeedBody
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.WatchAccountForVideoScreen
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.datasource.VideoFilterAssemblerSubscription
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.DividerThickness
import com.vitorpamplona.amethyst.ui.theme.FeedPadding
import com.vitorpamplona.amethyst.ui.theme.HorzPadding
import com.vitorpamplona.amethyst.ui.theme.Size5dp
import com.vitorpamplona.amethyst.ui.theme.StdVertSpacer
import com.vitorpamplona.amethyst.ui.theme.ThemeComparisonRow

@Composable
fun HomeScreen(
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    HomeScreen(
        videoFeedState = accountViewModel.feedStates.videoFeed,
        accountViewModel = accountViewModel,
        nav = nav,
    )
}

@Composable
fun HomeScreen(
    videoFeedState: FeedContentState,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    WatchAccountForVideoScreen(videoFeedContentState = videoFeedState, accountViewModel = accountViewModel)
    WatchLifecycleAndUpdateModel(videoFeedState)

    HomeFilterAssemblerSubscription(accountViewModel)
    VideoFilterAssemblerSubscription(accountViewModel)

    LaunchedEffect(accountViewModel) {
        val stories = accountViewModel.account.settings.defaultStoriesFollowList.value
        accountViewModel.account.settings.changeDefaultHomeFollowList(stories)
    }

    DisappearingScaffold(
        isInvertedLayout = false,
        topBar = null,
        bottomBar = {
            AppBottomBar(Route.Home, nav, accountViewModel) { route ->
                if (route == Route.Home) {
                    videoFeedState.sendToTop()
                } else {
                    nav.navBottomBar(route)
                }
            }
        },
        floatingButton = {
            FabBottomBarPadded(nav) {
                NewImageButton(
                    accountViewModel,
                    nav,
                    videoFeedState::sendToTop,
                    includeTextPost = true,
                )
            }
        },
        accountViewModel = accountViewModel,
    ) {
        VideoFeedBody(
            videoFeedContentState = videoFeedState,
            scrollKey = ScrollStateKeys.HOME_VIDEO_FEED,
            accountViewModel = accountViewModel,
            nav = nav,
            feedHeader = {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    StoriesTopBar(
                        accountViewModel = accountViewModel,
                        nav = nav,
                        alsoUpdateHomeFollowList = true,
                        applyStatusBarInsets = false,
                    )
                    HomeEphemeralStoryRings(accountViewModel, nav)
                    HorizontalDivider(thickness = DividerThickness)
                }
            },
        )
    }
}

@Composable
fun HomeScreenFloatingButton(
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val list =
        accountViewModel.account.settings.defaultHomeFollowList
            .collectAsStateWithLifecycle()

    when (val filter = list.value) {
        TopFilter.AroundMe -> {
            // Around Me uses private country/region authors — not geotagged posts.
            NewNoteButton(nav)
        }

        is TopFilter.Geohash -> {
            NewGeoPostButton(filter.tag, accountViewModel, nav)
        }

        is TopFilter.Hashtag -> {
            NewHashtagPostButton(filter.tag, accountViewModel, nav)
        }

        is TopFilter.InterestSet -> {
            NewNoteButton(nav)
        }

        else -> {
            NewNoteButton(nav)
        }
    }
}

@Composable
fun HomeFeeds(
    feedState: FeedContentState,
    routeForLastRead: String?,
    enablePullRefresh: Boolean = true,
    scrollStateKey: String? = null,
    liveSection: ChannelFeedContentState? = null,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val activeFilter by accountViewModel.account.settings.defaultHomeFollowList
        .collectAsStateWithLifecycle()
    val favoriteAlgoFeedAddresses by accountViewModel.account.favoriteAlgoFeedsList.flow
        .collectAsStateWithLifecycle()

    val onRefresh: () -> Unit = {
        feedState.invalidateData()
        if (activeFilter.usesAstroInterestOrder()) {
            accountViewModel.refreshAstroInterestSubscriptions(pullRefresh = true)
        }
        // Swiping down on Home should also re-issue the kind-5300 request(s) so the
        // DVM(s) produce fresh feeds, not just re-render whatever's cached.
        when (val filter = activeFilter) {
            is TopFilter.FavoriteAlgoFeed -> accountViewModel.refreshFavoriteAlgoFeed(filter.address)
            is TopFilter.AllFavoriteAlgoFeeds -> favoriteAlgoFeedAddresses.forEach { accountViewModel.refreshFavoriteAlgoFeed(it) }
            else -> Unit
        }
    }

    RefresheableHomeBox(onRefresh, enablePullRefresh) {
        SaveableFeedContentState(
            feedState,
            scrollStateKey,
        ) { listState ->
            WatchRevealChromeAtFeedTop(listState)
            WatchAstroScrollLoad(
                listState = listState,
                enabled = activeFilter.usesAstroInterestOrder(),
                accountViewModel = accountViewModel,
            )
            RenderFeedContentState(
                feedContentState = feedState,
                accountViewModel = accountViewModel,
                listState = listState,
                nav = nav,
                routeForLastRead = routeForLastRead,
                onLoaded = {
                    FeedLoaded(
                        it,
                        listState,
                        routeForLastRead,
                        liveSection,
                        feedState,
                        accountViewModel,
                        nav,
                    )
                },
                onEmpty = { HomeFeedEmpty(onRefresh) },
            )
        }
    }
}

@Composable
private fun RefresheableHomeBox(
    onRefresh: () -> Unit,
    enablePullRefresh: Boolean,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    if (enablePullRefresh) {
        RefresheableBox(onRefresh = onRefresh, content = content)
    } else {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize(),
            content = content,
        )
    }
}

@Composable
fun FeedLoaded(
    loaded: FeedState.Loaded,
    listState: LazyListState,
    routeForLastRead: String?,
    liveSection: ChannelFeedContentState? = null,
    feedContentState: FeedContentState? = null,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val liveItems by loaded.feed.collectAsStateWithLifecycle()
    val isScrolling =
        remember(listState) {
            derivedStateOf { listState.isScrollInProgress }
        }

    CompositionLocalProvider(LocalFeedListIsScrolling provides isScrolling) {
        val firstVisibleIndex by remember(listState) {
            derivedStateOf { listState.firstVisibleItemIndex }
        }
        LaunchedEffect(firstVisibleIndex) {
            TextToSpeechHelper.stopSpeaking()
        }
        LazyColumn(
            contentPadding = rememberFeedContentPadding(FeedPadding),
            state = listState,
        ) {
            if (liveSection != null) {
                item {
                    DisplayLiveBubbles(liveSection, accountViewModel, nav)
                    Spacer(StdVertSpacer)
                }
            }
            itemsIndexed(
                liveItems.list,
                key = { _, item -> item.idHex },
                contentType = { _, item -> item.event?.kind ?: -1 },
            ) { _, item ->
                Row(Modifier.fillMaxWidth()) {
                    NoteCompose(
                        item,
                        modifier = Modifier.fillMaxWidth(),
                        routeForLastRead = routeForLastRead,
                        isBoostedNote = false,
                        isHiddenFeed = liveItems.showHidden,
                        quotesLeft = 3,
                        instagramFeedLayout = true,
                        accountViewModel = accountViewModel,
                        nav = nav,
                    )
                }

                HorizontalDivider(
                    thickness = DividerThickness,
                )
            }
        }
    }
}

@Composable
fun DisplayLiveBubbles(
    liveSection: ChannelFeedContentState,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val feedState by liveSection.feedContent.collectAsStateWithLifecycle()

    when (val state = feedState) {
        is ChannelFeedState.Empty -> {}
        is ChannelFeedState.FeedError -> {}
        is ChannelFeedState.Loaded -> DisplayLiveBubbles(state, accountViewModel, nav)
        is ChannelFeedState.Loading -> {}
    }
}

@Composable
fun DisplayLiveBubbles(
    liveFeed: ChannelFeedState.Loaded,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val feed by liveFeed.feed.collectAsStateWithLifecycle()

    LazyRow(HorzPadding, horizontalArrangement = spacedBy(Size5dp)) {
        itemsIndexed(feed.list, key = { _, item -> item.hashCode() }) { _, item ->
            when (item) {
                is EphemeralChatChannel -> RenderEphemeralBubble(item, accountViewModel, nav)
                is LiveActivitiesChannel -> RenderLiveActivityBubble(item, accountViewModel, nav)
            }
        }
    }
}

@Preview
@Composable
fun HomeFeedEmptyPreview() {
    ThemeComparisonRow(
        toPreview = { HomeFeedEmpty {} },
    )
}

@Composable
fun HomeFeedEmpty(onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringRes(R.string.feed_is_empty))
        Spacer(modifier = StdVertSpacer)
        OutlinedButton(onClick = onRefresh) { Text(text = stringRes(R.string.refresh)) }
    }
}

@Composable
fun CheckIfVideoIsOnline(
    url: String,
    accountViewModel: AccountViewModel,
    whenOnline: @Composable (Boolean) -> Unit,
) {
    val online by produceState(
        initialValue = OnlineChecker.isOnlineCached(url),
        key1 = url,
    ) {
        val isOnline = accountViewModel.checkVideoIsOnline(url)
        if (value != isOnline) {
            value = isOnline
        }
    }

    whenOnline(online)
}

@Composable
fun CrossfadeCheckIfVideoIsOnline(
    url: String,
    accountViewModel: AccountViewModel,
    whenOnline: @Composable () -> Unit,
) {
    val online by produceState(
        initialValue = OnlineChecker.isOnlineCached(url),
        key1 = url,
    ) {
        val isOnline = accountViewModel.checkVideoIsOnline(url)
        if (value != isOnline) {
            value = isOnline
        }
    }

    CrossfadeIfEnabled(
        targetState = online,
        label = "CheckIfUrlIsOnline",
        accountViewModel = accountViewModel,
    ) {
        if (it) {
            whenOnline()
        }
    }
}

@Composable
fun WatchAccountForHomeScreen(
    liveFeedState: ChannelFeedContentState,
    newThreadsFeedState: FeedContentState,
    repliesFeedState: FeedContentState,
    everythingFeedState: FeedContentState,
    accountViewModel: AccountViewModel,
) {
    val homeFollowList by accountViewModel.account.liveHomeFollowLists.collectAsStateWithLifecycle()

    LaunchedEffect(accountViewModel, homeFollowList) {
        newThreadsFeedState.checkKeysInvalidateDataAndSendToTop()
        repliesFeedState.checkKeysInvalidateDataAndSendToTop()
        everythingFeedState.checkKeysInvalidateDataAndSendToTop()
        liveFeedState.checkKeysInvalidateDataAndSendToTop()
    }
}

enum class HomeTabContent {
    Notes,
    Stories,
}

@Immutable
class TabItem(
    val resource: Int,
    val feedState: FeedContentState,
    val routeForLastRead: String,
    val scrollStateKey: String,
    val forceEventKind: Int? = null,
    val useGridLayout: Boolean = false,
    val liveSection: ChannelFeedContentState? = null,
    val content: HomeTabContent = HomeTabContent.Notes,
)
