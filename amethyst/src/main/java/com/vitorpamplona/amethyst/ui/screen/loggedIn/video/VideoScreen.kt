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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.commons.ui.components.NoteCardSkeleton
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedContentState
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedState
import com.vitorpamplona.amethyst.commons.ui.layouts.rememberFeedContentPadding
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.tts.TextToSpeechHelper
import com.vitorpamplona.amethyst.ui.dal.usesAstroInterestOrder
import com.vitorpamplona.amethyst.ui.feeds.FeedEmpty
import com.vitorpamplona.amethyst.ui.feeds.LoadingFeedSkeleton
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
import com.vitorpamplona.amethyst.ui.screen.loggedIn.pictures.PictureCardCompose
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.VideoCardCompose
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.isReelPlayableVideo
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.datasource.VideoFilterAssemblerSubscription
import com.vitorpamplona.amethyst.ui.theme.DividerThickness
import com.vitorpamplona.amethyst.ui.theme.FeedPadding
import com.vitorpamplona.quartz.experimental.ephemeralStory.EphemeralStoryEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip68Picture.PictureEvent
import com.vitorpamplona.quartz.nip71Video.VideoEvent
import com.vitorpamplona.quartz.nip94FileMetadata.FileHeaderEvent

@Composable
fun VideoScreen(
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    VideoScreen(
        accountViewModel.feedStates.videoFeed,
        accountViewModel,
        nav,
    )
}

@Composable
fun VideoScreen(
    videoFeedContentState: FeedContentState,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    WatchLifecycleAndUpdateModel(videoFeedContentState)
    WatchAccountForVideoScreen(videoFeedContentState = videoFeedContentState, accountViewModel = accountViewModel)
    VideoFilterAssemblerSubscription(accountViewModel)

    DisappearingScaffold(
        isInvertedLayout = false,
        topBar = {
            StoriesTopBar(accountViewModel, nav)
        },
        bottomBar = {
            AppBottomBar(Route.Video, nav, accountViewModel) { route ->
                if (route == Route.Video) {
                    videoFeedContentState.sendToTop()
                } else {
                    nav.navBottomBar(route)
                }
            }
        },
        floatingButton = {
            FabBottomBarPadded(nav) {
                NewImageButton(accountViewModel, nav, videoFeedContentState::sendToTop)
            }
        },
        accountViewModel = accountViewModel,
    ) {
        RenderFeed(
            videoFeedContentState = videoFeedContentState,
            scrollKey = ScrollStateKeys.VIDEO_SCREEN,
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }
}

@Composable
fun WatchAccountForVideoScreen(
    videoFeedContentState: FeedContentState,
    accountViewModel: AccountViewModel,
) {
    val listState by accountViewModel.account.liveStoriesFollowLists.collectAsStateWithLifecycle()
    val hiddenUsers =
        accountViewModel.account.hiddenUsers.flow
            .collectAsStateWithLifecycle()

    LaunchedEffect(accountViewModel, listState, hiddenUsers) {
        videoFeedContentState.checkKeysInvalidateDataAndSendToTop()
    }
}

@Composable
fun VideoFeedBody(
    videoFeedContentState: FeedContentState,
    scrollKey: String?,
    accountViewModel: AccountViewModel,
    nav: INav,
    revealChromeOnlyAtTop: Boolean = false,
    feedHeader: (@Composable () -> Unit)? = null,
) {
    val storiesFilter by accountViewModel.account.settings.defaultStoriesFollowList
        .collectAsStateWithLifecycle()
    val onRefresh: () -> Unit = {
        videoFeedContentState.invalidateData()
        if (storiesFilter.usesAstroInterestOrder()) {
            accountViewModel.refreshAstroInterestSubscriptions(pullRefresh = true)
        }
    }
    RefresheableBox(onRefresh = onRefresh) {
        SaveableFeedContentState(
            videoFeedContentState,
            scrollStateKey = scrollKey,
        ) { listState ->
            WatchAstroScrollLoad(
                listState = listState,
                enabled = storiesFilter.usesAstroInterestOrder(),
                accountViewModel = accountViewModel,
            )
            RenderFeedContentState(
                feedContentState = videoFeedContentState,
                accountViewModel = accountViewModel,
                listState = listState,
                nav = nav,
                routeForLastRead = "VideosFeed",
                onLoaded = { loaded ->
                    VideoFeedLoaded(
                        loaded = loaded,
                        listState = listState,
                        feedContentState = videoFeedContentState,
                        accountViewModel = accountViewModel,
                        nav = nav,
                        revealChromeOnlyAtTop = revealChromeOnlyAtTop,
                        feedHeader = feedHeader,
                    )
                },
                onEmpty = {
                    if (feedHeader != null) {
                        VideoFeedLoadedHeader(
                            listState = listState,
                            feedHeader = feedHeader,
                        )
                    } else {
                        FeedEmpty(onRefresh)
                    }
                },
                onLoading = {
                    // Keep the header (top bar + stories) visible while the first
                    // page loads, so a cold start is never a blank screen.
                    if (feedHeader != null) {
                        VideoFeedLoadingHeader(
                            listState = listState,
                            feedHeader = feedHeader,
                        )
                    } else {
                        LoadingFeedSkeleton()
                    }
                },
            )
        }
    }
}

@Composable
private fun RenderFeed(
    videoFeedContentState: FeedContentState,
    scrollKey: String?,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    VideoFeedBody(videoFeedContentState, scrollKey, accountViewModel, nav)
}

@Composable
fun VideoFeedLoaded(
    loaded: FeedState.Loaded,
    listState: LazyListState,
    feedContentState: FeedContentState? = null,
    accountViewModel: AccountViewModel,
    nav: INav,
    revealChromeOnlyAtTop: Boolean = false,
    feedHeader: (@Composable () -> Unit)? = null,
) {
    val items by loaded.feed.collectAsStateWithLifecycle()

    if (revealChromeOnlyAtTop) {
        WatchRevealChromeAtFeedTop(listState)
    }

    val firstVisibleIndex by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    LaunchedEffect(firstVisibleIndex) {
        TextToSpeechHelper.stopSpeaking()
    }

    val innerPadding =
        if (feedHeader != null) {
            PaddingValues(top = 0.dp, bottom = 10.dp)
        } else {
            FeedPadding
        }

    LazyColumn(
        contentPadding = rememberFeedContentPadding(innerPadding),
        state = listState,
    ) {
        if (feedHeader != null) {
            item(key = "feed_header", contentType = "feed_header") {
                feedHeader()
            }
        }
        itemsIndexed(
            items.list,
            key = { _, item -> item.idHex },
            contentType = { _, item -> item.event?.kind ?: -1 },
        ) { _, item ->
            val event = item.event
            when {
                item.event is PictureEvent -> {
                    PictureCardCompose(
                        baseNote = item,
                        accountViewModel = accountViewModel,
                        nav = nav,
                    )
                    VideoFeedCardFooter()
                }

                item.event is VideoEvent -> {
                    VideoCardCompose(item, accountViewModel, nav)
                    VideoFeedCardFooter()
                }

                item.event is FileHeaderEvent -> {
                    FileHeaderCardCompose(item, accountViewModel, nav)
                    VideoFeedCardFooter()
                }

                item.event is EphemeralStoryEvent -> {
                    if ((item.event as EphemeralStoryEvent).isVideo()) {
                        VideoCardCompose(item, accountViewModel, nav)
                    } else {
                        PictureCardCompose(
                            baseNote = item,
                            accountViewModel = accountViewModel,
                            nav = nav,
                        )
                    }
                    VideoFeedCardFooter()
                }

                event is TextNoteEvent && event.isReelPlayableVideo() -> {
                    VideoCardCompose(item, accountViewModel, nav)
                    VideoFeedCardFooter()
                }

                else -> {
                    VideoFeedNoteCard(
                        item = item,
                        showHidden = items.showHidden,
                        accountViewModel = accountViewModel,
                        nav = nav,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoFeedLoadedHeader(
    listState: LazyListState,
    feedHeader: @Composable () -> Unit,
) {
    LazyColumn(
        contentPadding = rememberFeedContentPadding(PaddingValues(top = 0.dp, bottom = 10.dp)),
        state = listState,
    ) {
        item(key = "feed_header", contentType = "feed_header") {
            feedHeader()
        }
    }
}

@Composable
private fun VideoFeedLoadingHeader(
    listState: LazyListState,
    feedHeader: @Composable () -> Unit,
) {
    LazyColumn(
        contentPadding = rememberFeedContentPadding(PaddingValues(top = 0.dp, bottom = 10.dp)),
        state = listState,
    ) {
        item(key = "feed_header", contentType = "feed_header") {
            feedHeader()
        }
        items(3, key = { "skeleton_$it" }, contentType = { "skeleton" }) {
            NoteCardSkeleton(modifier = Modifier.padding(FeedPadding))
        }
    }
}

@Composable
private fun VideoFeedCardFooter() {
    HorizontalDivider(
        thickness = DividerThickness,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun VideoFeedNoteCard(
    item: Note,
    showHidden: Boolean,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    NoteCompose(
        item,
        modifier = Modifier.fillMaxWidth(),
        routeForLastRead = "VideosFeed",
        isBoostedNote = false,
        isHiddenFeed = showHidden,
        quotesLeft = 3,
        instagramFeedLayout = true,
        accountViewModel = accountViewModel,
        nav = nav,
    )
    VideoFeedCardFooter()
}
