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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.newthreads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.richtext.RichTextParser
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedState
import com.vitorpamplona.amethyst.commons.ui.feeds.isRenderableRepost
import com.vitorpamplona.amethyst.commons.ui.layouts.rememberFeedContentPadding
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.event.EventFinderFilterAssemblerSubscription
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.event.observeNote
import com.vitorpamplona.amethyst.ui.components.LoadNote
import com.vitorpamplona.amethyst.ui.feeds.FeedEmpty
import com.vitorpamplona.amethyst.ui.feeds.FeedError
import com.vitorpamplona.amethyst.ui.feeds.LoadingFeed
import com.vitorpamplona.amethyst.ui.feeds.PrefetchLoadedFeedMedia
import com.vitorpamplona.amethyst.ui.feeds.RefresheableBox
import com.vitorpamplona.amethyst.ui.feeds.WatchScrollToTop
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.note.LoadDecryptedContent
import com.vitorpamplona.amethyst.ui.note.RepostedIcon
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.gallery.GalleryThumbnail
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.gallery.GridVideoBadge
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.newthreads.dal.UserProfileNewThreadsFeedViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.pinnedNotes.dal.UserProfilePinnedNotesFeedViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.FeedPadding
import com.vitorpamplona.amethyst.ui.theme.Size16Modifier
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip18Reposts.GenericRepostEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip18Reposts.quotes.taggedQuoteIds
import com.vitorpamplona.quartz.nip68Picture.PictureEvent
import com.vitorpamplona.quartz.nip71Video.VideoEvent
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent
import com.vitorpamplona.quartz.nip92IMeta.IMetaTag
import com.vitorpamplona.quartz.nip94FileMetadata.FileHeaderEvent

private val ImageUrlInText =
    Regex(
        """https?://[^\s]+\.(?:jpg|jpeg|png|gif|webp|avif)(?:\?[^\s]*)?""",
        RegexOption.IGNORE_CASE,
    )

private val NostrOrHttpOnlyLine =
    Regex(
        """^(?:https?://\S+|nostr:\S+)$""",
        RegexOption.IGNORE_CASE,
    )

@Composable
fun TabNotesNewThreads(
    feedViewModel: UserProfileNewThreadsFeedViewModel,
    pinnedNotesFeedViewModel: UserProfilePinnedNotesFeedViewModel,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    LaunchedEffect(Unit) { pinnedNotesFeedViewModel.invalidateData() }

    val listState = rememberLazyGridState()

    RefresheableBox(feedViewModel, enablePullRefresh = false) {
        WatchScrollToTop(feedViewModel.feedState, listState)

        val feedState by feedViewModel.feedState.feedContent.collectAsStateWithLifecycle()
        val pinnedFeedState by pinnedNotesFeedViewModel.feedState.feedContent.collectAsStateWithLifecycle()

        when (val state = feedState) {
            is FeedState.Empty -> {
                val pinnedLoaded = pinnedFeedState as? FeedState.Loaded
                if (pinnedLoaded != null) {
                    val pinnedItems by pinnedLoaded.feed.collectAsStateWithLifecycle()
                    if (pinnedItems.list.isNotEmpty()) {
                        ProfilePostsGrid(
                            pinnedNotes = pinnedItems.list,
                            posts = emptyList(),
                            listState = listState,
                            accountViewModel = accountViewModel,
                            nav = nav,
                        )
                    } else {
                        FeedEmpty { feedViewModel.invalidateData() }
                    }
                } else {
                    FeedEmpty { feedViewModel.invalidateData() }
                }
            }

            is FeedState.FeedError -> {
                FeedError(state.errorMessage) { feedViewModel.invalidateData() }
            }

            is FeedState.Loaded -> {
                PrefetchLoadedFeedMedia(state, listState, accountViewModel)
                val feedItems by state.feed.collectAsStateWithLifecycle()
                val pinnedLoaded = pinnedFeedState as? FeedState.Loaded
                val pinnedItems =
                    if (pinnedLoaded != null) {
                        pinnedLoaded.feed
                            .collectAsStateWithLifecycle()
                            .value.list
                    } else {
                        emptyList()
                    }

                ProfilePostsGrid(
                    pinnedNotes = pinnedItems,
                    posts = feedItems.list,
                    listState = listState,
                    accountViewModel = accountViewModel,
                    nav = nav,
                )
            }

            is FeedState.Loading -> {
                LoadingFeed()
            }
        }
    }
}

@Composable
private fun ProfilePostsGrid(
    pinnedNotes: List<Note>,
    posts: List<Note>,
    listState: LazyGridState,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val pinnedIds = remember(pinnedNotes) { pinnedNotes.map { it.idHex }.toSet() }
    val gridItems =
        remember(pinnedNotes, posts) {
            pinnedNotes + posts.filter { it.idHex !in pinnedIds }
        }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = listState,
        contentPadding = rememberFeedContentPadding(FeedPadding),
        verticalArrangement = spacedBy(2.dp),
        horizontalArrangement = spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = gridItems,
            key = { it.idHex },
        ) { note ->
            ProfilePostGridCard(
                note = note,
                isPinned = note.idHex in pinnedIds,
                modifier =
                    Modifier
                        .aspectRatio(1f)
                        .fillMaxWidth(),
                accountViewModel = accountViewModel,
                nav = nav,
            )
        }
    }
}

@Composable
private fun ProfilePostGridCard(
    note: Note,
    isPinned: Boolean,
    modifier: Modifier = Modifier,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val noteState by observeNote(note, accountViewModel)
    val liveNote = noteState.note
    val isBoost = liveNote.event.isRenderableRepost()
    val quoteId =
        remember(liveNote.event?.id) {
            liveNote.event?.taggedQuoteIds()?.lastOrNull { it.length == 64 }
        }

    when {
        isBoost -> {
            val boosted =
                liveNote.replyTo?.lastOrNull { it.event !is CommunityDefinitionEvent }
            if (boosted != null) {
                EventFinderFilterAssemblerSubscription(boosted, accountViewModel)
                val boostedState by observeNote(boosted, accountViewModel)
                ProfilePostGridCardBody(
                    sourceNote = liveNote,
                    displayNote = boostedState.note,
                    fallbackEvent = embeddedRepostEvent(liveNote.event),
                    isRepost = true,
                    isPinned = isPinned,
                    modifier = modifier,
                    accountViewModel = accountViewModel,
                    nav = nav,
                )
            } else {
                ProfilePostGridCardBody(
                    sourceNote = liveNote,
                    displayNote = liveNote,
                    fallbackEvent = embeddedRepostEvent(liveNote.event),
                    isRepost = true,
                    isPinned = isPinned,
                    modifier = modifier,
                    accountViewModel = accountViewModel,
                    nav = nav,
                )
            }
        }
        quoteId != null -> {
            LoadNote(baseNoteHex = quoteId, accountViewModel = accountViewModel) { quoted ->
                if (quoted != null) {
                    EventFinderFilterAssemblerSubscription(quoted, accountViewModel)
                    val quotedState by observeNote(quoted, accountViewModel)
                    val quotedNote = quotedState.note
                    ProfilePostGridCardBody(
                        sourceNote = liveNote,
                        displayNote = if (quotedNote.event != null) quotedNote else liveNote,
                        fallbackEvent = null,
                        isRepost = true,
                        isPinned = isPinned,
                        modifier = modifier,
                        accountViewModel = accountViewModel,
                        nav = nav,
                    )
                } else {
                    ProfilePostGridCardBody(
                        sourceNote = liveNote,
                        displayNote = liveNote,
                        fallbackEvent = null,
                        isRepost = true,
                        isPinned = isPinned,
                        modifier = modifier,
                        accountViewModel = accountViewModel,
                        nav = nav,
                    )
                }
            }
        }
        else -> {
            ProfilePostGridCardBody(
                sourceNote = liveNote,
                displayNote = liveNote,
                fallbackEvent = null,
                isRepost = false,
                isPinned = isPinned,
                modifier = modifier,
                accountViewModel = accountViewModel,
                nav = nav,
            )
        }
    }
}

@Composable
private fun ProfilePostGridCardBody(
    sourceNote: Note,
    displayNote: Note,
    fallbackEvent: Event?,
    isRepost: Boolean,
    isPinned: Boolean,
    modifier: Modifier = Modifier,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val previewEvent =
        remember(displayNote.event?.id, fallbackEvent?.id) {
            displayNote.event ?: fallbackEvent
        }
    val previewUrl = remember(previewEvent?.id) { firstPreviewImageUrl(previewEvent) }
    val shape = RoundedCornerShape(2.dp)

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { nav.nav(Route.Note(sourceNote.idHex)) },
    ) {
        when {
            previewUrl != null -> {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            hasNativeGalleryMedia(displayNote.event) -> {
                GalleryThumbnail(
                    baseNote = displayNote,
                    accountViewModel = accountViewModel,
                    nav = nav,
                )
            }
            hasNativeGalleryMedia(fallbackEvent) && displayNote.event == null -> {
                val embeddedPreview = firstPreviewImageUrl(fallbackEvent)
                if (embeddedPreview != null) {
                    AsyncImage(
                        model = embeddedPreview,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TextPostTile(content = "…")
                }
            }
            else -> {
                LoadDecryptedContent(displayNote, accountViewModel) { content ->
                    TextPostTile(content = content.trim())
                }
            }
        }

        // GalleryThumbnail already draws its own badge; AsyncImage paths need one here.
        if (previewEvent is VideoEvent && previewUrl != null) {
            GridVideoBadge()
        }

        if (isRepost || isPinned) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 5.dp, vertical = 3.dp),
            ) {
                when {
                    isPinned -> {
                        Icon(
                            symbol = MaterialSymbols.PushPin,
                            contentDescription = stringRes(R.string.pinned_notes),
                            tint = Color.White,
                            modifier = Size16Modifier,
                        )
                    }
                    else -> {
                        RepostedIcon(Size16Modifier, Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPostTile(content: String) {
    val preview =
        remember(content) {
            content
                .lineSequence()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                        !line.startsWith("http", ignoreCase = true) &&
                        !line.startsWith("nostr:", ignoreCase = true) &&
                        !NostrOrHttpOnlyLine.matches(line)
                }.take(4)
                .joinToString("\n")
                .ifBlank { "…" }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface,
                            ),
                    ),
                ).padding(10.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column {
            Text(
                text = preview,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun embeddedRepostEvent(event: Event?): Event? =
    when (event) {
        is RepostEvent -> event.containedPost()
        is GenericRepostEvent -> event.containedPost()
        else -> null
    }

private fun hasNativeGalleryMedia(event: Event?): Boolean = event is PictureEvent || event is VideoEvent

private fun firstPreviewImageUrl(event: Event?): String? {
    if (event == null) return null

    when (event) {
        is PictureEvent -> return event.imetaTags().firstOrNull()?.url
        is VideoEvent -> {
            val imeta = event.imetaTags().firstOrNull()
            return imeta?.image?.firstOrNull() ?: imeta?.url?.takeIf { RichTextParser.isImageUrl(it) }
        }
        is FileHeaderEvent -> {
            return event.image()?.imageUrl
                ?: event.thumb()?.imageUrl
                ?: event.url()?.takeIf { RichTextParser.isImageUrl(it) }
        }
        is RepostEvent, is GenericRepostEvent -> {
            return firstPreviewImageUrl(embeddedRepostEvent(event))
        }
    }

    // imeta / image tags on regular notes
    event.tags.forEach { tag ->
        when (tag.getOrNull(0)) {
            "image" -> tag.getOrNull(1)?.let { return it }
            "imeta" -> {
                IMetaTag.parse(tag)?.firstOrNull()?.let { meta ->
                    meta.properties["image"]?.firstOrNull()?.let { return it }
                    if (RichTextParser.isImageUrl(meta.url)) return meta.url
                }
            }
        }
    }

    return ImageUrlInText.find(event.content)?.value
}
