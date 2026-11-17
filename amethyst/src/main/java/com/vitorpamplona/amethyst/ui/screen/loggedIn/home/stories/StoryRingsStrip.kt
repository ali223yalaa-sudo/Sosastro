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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.home.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.ui.components.ShimmerPlaceholder
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedContentState
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedState
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.model.User
import com.vitorpamplona.amethyst.ui.actions.NewMediaModel
import com.vitorpamplona.amethyst.ui.actions.NewMediaView
import com.vitorpamplona.amethyst.ui.actions.uploads.GallerySelect
import com.vitorpamplona.amethyst.ui.actions.uploads.SelectedMedia
import com.vitorpamplona.amethyst.ui.feeds.WatchLifecycleAndUpdateModel
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.note.ObserveAndDrawInnerUserPicture
import com.vitorpamplona.amethyst.ui.note.UsernameDisplay
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.stories.datasource.EphemeralStoryFilterAssemblerSubscription
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.Size10dp
import com.vitorpamplona.quartz.experimental.ephemeralStory.EphemeralStoryEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Avatar diameter inside the ring — Instagram-like (~64dp). */
val StoryAvatarSize: Dp = 64.dp

/** Outer ring diameter including stroke padding. */
val StoryRingOuterSize: Dp = 72.dp

private val StoryRingStroke = 2.5.dp
private val StoryRingGap = 12.dp
private val StoryStripBottomPad = 8.dp
private val EmptyRing = Color(0xFFB0B0B0)

/** Remaining lifetime of a 24h story → ring color (fresh = green, <5h = red). */
fun storyRingColor(remainingSec: Long): Color =
    when {
        remainingSec >= 18 * 3600L -> Color(0xFF22C55E)
        remainingSec >= 12 * 3600L -> Color(0xFF14B8A6)
        remainingSec >= 8 * 3600L -> Color(0xFFEAB308)
        remainingSec >= 5 * 3600L -> Color(0xFFF97316)
        else -> Color(0xFFEF4444)
    }

fun storyRemainingSeconds(
    createdAt: Long,
    now: Long = TimeUtils.now(),
): Long = (createdAt + EphemeralStoryEvent.EXPIRY_SECONDS - now).coerceAtLeast(0)

@Immutable
data class StoryAuthorRing(
    val authorHex: HexKey,
    val author: User?,
    val stories: ImmutableList<Note>,
    val latestCreatedAt: Long,
    val hasUnseen: Boolean,
    val isMine: Boolean,
)

@Composable
fun HomeEphemeralStoryRings(
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val feedState = accountViewModel.feedStates.ephemeralStoriesFeed
    WatchLifecycleAndUpdateModel(feedState)
    EphemeralStoryFilterAssemblerSubscription(accountViewModel)

    val homeFollowList by accountViewModel.account.liveHomeFollowLists.collectAsStateWithLifecycle()
    LaunchedEffect(accountViewModel, homeFollowList) {
        feedState.checkKeysInvalidateDataAndSendToTop()
    }

    DisplayEphemeralStoryRings(feedState, accountViewModel, nav)
}

@Composable
fun DisplayEphemeralStoryRings(
    feedState: FeedContentState,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val feedContent by feedState.feedContent.collectAsStateWithLifecycle()

    when (val state = feedContent) {
        is FeedState.Loaded -> DisplayEphemeralStoryRingsLoaded(state, accountViewModel, nav)
        is FeedState.Loading -> {
            // Shimmer rings while the first story page lands, so the strip
            // reads as "loading" rather than "you have no stories".
            StoryRingsLoadingRow(accountViewModel, nav)
        }
        else -> {
            // Always show "Your story" even when empty.
            StoryRingsRow(
                rings = persistentListOf(),
                accountViewModel = accountViewModel,
                nav = nav,
            )
        }
    }
}

@Composable
private fun StoryRingsLoadingRow(
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    // "Your story" stays real and tappable; only the unknown other-author
    // rings are replaced by placeholders.
    val myUser = accountViewModel.account.userProfile()
    LazyRow(
        contentPadding = PaddingValues(horizontal = Size10dp, vertical = StoryStripBottomPad),
        horizontalArrangement = Arrangement.spacedBy(StoryRingGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "my-story") {
            MyStoryRing(
                ring = null,
                myUser = myUser,
                nowSec = 0L,
                treatAsHavingStories = false,
                accountViewModel = accountViewModel,
                onOpenStories = {},
                onAddStory = {},
            )
        }
        items(4) {
            StoryRingSkeleton()
        }
    }
}

@Composable
private fun StoryRingSkeleton() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ShimmerPlaceholder(
            Modifier
                .size(StoryRingOuterSize)
                .clip(CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        ShimmerPlaceholder(Modifier.width(48.dp).height(10.dp))
    }
}

@Composable
private fun DisplayEphemeralStoryRingsLoaded(
    loaded: FeedState.Loaded,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val feed by loaded.feed.collectAsStateWithLifecycle()
    val myHex = accountViewModel.account.userProfile().pubkeyHex

    // Collect last-read map so seen/unseen + order update immediately after viewing.
    val lastReadMap by accountViewModel.account.settings.lastReadPerRoute
        .collectAsStateWithLifecycle()

    val rings by remember(feed.list, myHex, lastReadMap) {
        derivedStateOf {
            buildStoryRings(feed.list, myHex) { authorHex, latest ->
                val last = lastReadMap[ephemeralStorySeenRoute(authorHex)]?.value ?: 0L
                latest > last
            }
        }
    }

    StoryRingsRow(
        rings = rings.toImmutableList(),
        accountViewModel = accountViewModel,
        nav = nav,
    )
}

fun buildStoryRings(
    notes: List<Note>,
    myHex: HexKey,
    hasUnseen: (authorHex: HexKey, latestCreatedAt: Long) -> Boolean,
): List<StoryAuthorRing> {
    val byAuthor =
        notes
            .filter { it.event is EphemeralStoryEvent }
            .groupBy { it.author?.pubkeyHex ?: it.event?.pubKey }
            .filterKeys { it != null }
            .map { (authorHex, authorNotes) ->
                val hex = authorHex!!
                val sorted = authorNotes.sortedBy { it.createdAt() ?: 0L }
                val latest = sorted.maxOfOrNull { it.createdAt() ?: 0L } ?: 0L
                StoryAuthorRing(
                    authorHex = hex,
                    author = sorted.firstOrNull()?.author,
                    stories = sorted.toImmutableList(),
                    latestCreatedAt = latest,
                    hasUnseen = hasUnseen(hex, latest),
                    isMine = hex == myHex,
                )
            }

    val mine = byAuthor.filter { it.isMine }
    val othersUnseen = byAuthor.filter { !it.isMine && it.hasUnseen }.sortedByDescending { it.latestCreatedAt }
    val othersSeen = byAuthor.filter { !it.isMine && !it.hasUnseen }.sortedByDescending { it.latestCreatedAt }
    return mine + othersUnseen + othersSeen
}

@Composable
private fun StoryRingsRow(
    rings: ImmutableList<StoryAuthorRing>,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    var viewerStartIndex by remember { mutableStateOf<Int?>(null) }
    var wantsGallery by remember { mutableStateOf(false) }
    var pickedURIs by remember { mutableStateOf<ImmutableList<SelectedMedia>>(persistentListOf()) }
    var ownStoryPending by remember { mutableStateOf(false) }
    var nowSec by remember { mutableLongStateOf(TimeUtils.now()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = TimeUtils.now()
        }
    }

    val postViewModel: NewMediaModel = viewModel()
    postViewModel.onceUploaded {
        ownStoryPending = true
        scope.launch(Dispatchers.IO) {
            delay(400)
            withContext(Dispatchers.Main) {
                accountViewModel.feedStates.ephemeralStoriesFeed.invalidateData()
            }
        }
    }

    val myUser = accountViewModel.account.userProfile()
    val myRing = rings.firstOrNull { it.isMine }
    val otherRings = rings.filter { !it.isMine }

    LaunchedEffect(myRing?.stories?.size) {
        if (myRing != null && myRing.stories.isNotEmpty()) {
            ownStoryPending = false
        }
    }

    if (wantsGallery) {
        GallerySelect(
            onImageUri = { uri ->
                wantsGallery = false
                pickedURIs = uri
            },
        )
    }

    if (pickedURIs.isNotEmpty()) {
        postViewModel.publishAsEphemeralStory = true
        NewMediaView(
            uris = pickedURIs,
            onClose = { pickedURIs = persistentListOf() },
            postViewModel = postViewModel,
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }

    val viewerRings =
        remember(rings) {
            rings.filter { it.stories.isNotEmpty() }.toImmutableList()
        }

    viewerStartIndex?.let { start ->
        if (viewerRings.isNotEmpty()) {
            EphemeralStoryViewer(
                rings = viewerRings,
                startAuthorIndex = start.coerceIn(0, viewerRings.lastIndex),
                accountViewModel = accountViewModel,
                nav = nav,
                onDismiss = { viewerStartIndex = null },
            )
        } else {
            viewerStartIndex = null
        }
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = Size10dp, vertical = StoryStripBottomPad),
        horizontalArrangement = Arrangement.spacedBy(StoryRingGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "my-story") {
            MyStoryRing(
                ring = myRing,
                myUser = myUser,
                nowSec = nowSec,
                treatAsHavingStories = ownStoryPending,
                accountViewModel = accountViewModel,
                onOpenStories = {
                    val idx = viewerRings.indexOfFirst { it.isMine }
                    if (idx >= 0) viewerStartIndex = idx
                },
                onAddStory = { wantsGallery = true },
            )
        }
        itemsIndexed(otherRings, key = { _, item -> item.authorHex }) { _, ring ->
            OtherStoryRing(
                ring = ring,
                nowSec = nowSec,
                accountViewModel = accountViewModel,
                onClick = {
                    val idx = viewerRings.indexOfFirst { it.authorHex == ring.authorHex }
                    if (idx >= 0) viewerStartIndex = idx
                },
            )
        }
    }
}

@Composable
private fun MyStoryRing(
    ring: StoryAuthorRing?,
    myUser: User,
    nowSec: Long,
    treatAsHavingStories: Boolean,
    accountViewModel: AccountViewModel,
    onOpenStories: () -> Unit,
    onAddStory: () -> Unit,
) {
    val hasStories = (ring != null && ring.stories.isNotEmpty()) || treatAsHavingStories
    val unseen = ring?.hasUnseen == true || (treatAsHavingStories && ring == null)
    val remaining = ring?.latestCreatedAt?.let { storyRemainingSeconds(it, nowSec) }
    val color =
        when {
            hasStories && unseen && remaining != null && remaining > 0 -> storyRingColor(remaining)
            hasStories -> EmptyRing
            else -> null
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(StoryRingOuterSize + 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            StoryHalo(
                ringColor = color,
                remainingLabel =
                    remaining
                        ?.takeIf { unseen && it > 0 }
                        ?.let { remainingLabel(it) },
                onClick = {
                    if (hasStories) onOpenStories() else onAddStory()
                },
            ) {
                ObserveAndDrawInnerUserPicture(
                    user = myUser,
                    size = StoryAvatarSize,
                    accountViewModel = accountViewModel,
                )
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .zIndex(2f)
                        .offset(x = (-2).dp, y = (-2).dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable(onClick = onAddStory),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    symbol = MaterialSymbols.Add,
                    contentDescription = stringRes(R.string.add_to_story),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringRes(R.string.your_story),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(StoryRingOuterSize + 8.dp),
        )
    }
}

@Composable
private fun OtherStoryRing(
    ring: StoryAuthorRing,
    nowSec: Long,
    accountViewModel: AccountViewModel,
    onClick: () -> Unit,
) {
    val author = ring.author
    val remaining = storyRemainingSeconds(ring.latestCreatedAt, nowSec)
    val color =
        if (ring.hasUnseen && remaining > 0) {
            storyRingColor(remaining)
        } else {
            EmptyRing
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .width(StoryRingOuterSize + 8.dp)
                .clickable(onClick = onClick),
    ) {
        StoryHalo(
            ringColor = color,
            remainingLabel =
                remaining.takeIf { ring.hasUnseen && it > 0 }?.let { remainingLabel(it) },
            onClick = onClick,
        ) {
            if (author != null) {
                ObserveAndDrawInnerUserPicture(
                    user = author,
                    size = StoryAvatarSize,
                    accountViewModel = accountViewModel,
                )
            } else {
                Box(
                    Modifier
                        .size(StoryAvatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (author != null) {
            UsernameDisplay(
                baseUser = author,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                accountViewModel = accountViewModel,
                weight = Modifier.width(StoryRingOuterSize + 8.dp),
            )
        } else {
            Text(
                text = ring.authorHex.take(8),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(StoryRingOuterSize + 8.dp),
            )
        }
    }
}

@Composable
private fun remainingLabel(remainingSec: Long): String {
    val hours = remainingSec / 3600L
    return if (hours >= 1L) {
        stringRes(R.string.story_hours_left, hours.toInt())
    } else {
        stringRes(R.string.story_minutes_left, (remainingSec / 60L).coerceAtLeast(1L).toInt())
    }
}

@Composable
private fun StoryHalo(
    ringColor: Color?,
    remainingLabel: String?,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    val stroke = ringColor ?: EmptyRing
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(StoryRingOuterSize + 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(StoryRingOuterSize)
                    .border(StoryRingStroke, stroke, CircleShape)
                    .padding(StoryRingStroke + 1.dp)
                    .border(2.dp, bg, CircleShape)
                    .clickable(onClick = onClick),
        ) {
            content()
        }
        if (!remainingLabel.isNullOrBlank() && ringColor != null) {
            Text(
                text = remainingLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(1f)
                        .background(ringColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}
