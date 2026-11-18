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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.richtext.BaseMediaContent
import com.vitorpamplona.amethyst.commons.richtext.MediaUrlVideo
import com.vitorpamplona.amethyst.commons.ui.layouts.LocalDisappearingScaffoldPadding
import com.vitorpamplona.amethyst.model.MediaAspectRatioCache
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.playback.composable.controls.PersistentMuteButton
import com.vitorpamplona.amethyst.ui.components.BlurhashBackdrop
import com.vitorpamplona.amethyst.ui.components.ContentWarningGate
import com.vitorpamplona.amethyst.ui.components.ZoomableContentView
import com.vitorpamplona.amethyst.ui.components.collectContentWarningReasons
import com.vitorpamplona.amethyst.ui.components.mediaSizingModifier
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.routeFor
import com.vitorpamplona.amethyst.ui.note.FeedCardCaption
import com.vitorpamplona.amethyst.ui.note.LoadAndDisplayZapraiser
import com.vitorpamplona.amethyst.ui.note.NoteAuthorPicture
import com.vitorpamplona.amethyst.ui.note.NoteUsernameDisplay
import com.vitorpamplona.amethyst.ui.note.ReactionsRow
import com.vitorpamplona.amethyst.ui.note.ShortsReelsSideReactions
import com.vitorpamplona.amethyst.ui.note.elements.MoreOptionsButton
import com.vitorpamplona.amethyst.ui.note.elements.TimeAgoStyle
import com.vitorpamplona.amethyst.ui.note.elements.ToggleableTimeAgoText
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.firstReelVideoMeta
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.UserCardHeader
import com.vitorpamplona.amethyst.ui.theme.Size35dp
import com.vitorpamplona.amethyst.ui.theme.VolumeBottomIconSize
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip36SensitiveContent.isSensitiveOrNSFW
import com.vitorpamplona.quartz.nip71Video.VideoEvent
import kotlinx.coroutines.delay

private val ReelsOverlayWhite = Color.White

/**
 * Breathing room between the reels overlays (author row, caption, reaction column) and the
 * bottom bar, so nothing ever touches the bottom edge of the screen. The scaffold already
 * reserves the bar; this is the extra, deliberately small gap on top of it.
 */
private val ReelsBottomSafeGap = 18.dp

/**
 * Vertical space kept clear at the bottom-right of a reel so the persistent mute toggle
 * never sits on top of the side reaction column (which is anchored to the same corner).
 */
private val ReelsMuteButtonReserve = 64.dp

@Composable
fun VideoCardCompose(
    baseNote: Note,
    accountViewModel: AccountViewModel,
    nav: INav,
    modifier: Modifier = Modifier,
    reelsLayout: Boolean = false,
    isActivePage: Boolean = true,
    onTapAction: (() -> Unit)? = null,
) {
    val event = baseNote.event ?: return
    val reelMeta = remember(baseNote.idHex, event.id) { event.firstReelVideoMeta() } ?: return
    val backgroundColor = remember { mutableStateOf(Color.Transparent) }

    if (reelsLayout) {
        val scaffoldPadding = LocalDisappearingScaffoldPadding.current
        val bottomOverlayPadding = scaffoldPadding.calculateBottomPadding() + ReelsBottomSafeGap
        val showHeartState = remember(baseNote.idHex) { mutableStateOf(false) }
        LaunchedEffect(showHeartState.value) {
            if (showHeartState.value) {
                delay(800)
                showHeartState.value = false
            }
        }

        // Same media sizing as Stories (FillWidth + aspect ratio) — Crop was zooming/distorting.
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            VideoCardImage(
                note = baseNote,
                event = event,
                reelMeta = reelMeta,
                backgroundColor = backgroundColor,
                accountViewModel = accountViewModel,
                contentScale = ContentScale.FillWidth,
                showControls = false,
                reelsGestures = true,
                startUnmuted = isActivePage,
                onDoubleTapAction =
                    if (isActivePage) {
                        {
                            val reaction = "+"
                            val alreadyLiked =
                                baseNote
                                    .allReactionsOfContentByAuthor(accountViewModel.userProfile(), reaction)
                                    .isNotEmpty()
                            if (!alreadyLiked) {
                                accountViewModel.reactToOrDelete(baseNote, reaction)
                            }
                            showHeartState.value = true
                        }
                    } else {
                        null
                    },
            )

            if (isActivePage && showHeartState.value) {
                Icon(
                    symbol = MaterialSymbols.Favorite,
                    contentDescription = null,
                    tint = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(96.dp),
                )
            }

            // Heavy reaction observers only on the settled page — neighbors stay video-only.
            if (isActivePage) {
                ShortsReelsSideReactions(
                    baseNote = baseNote,
                    accountViewModel = accountViewModel,
                    nav = nav,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    tint = ReelsOverlayWhite,
                    bottomContentPadding = bottomOverlayPadding + ReelsMuteButtonReserve,
                )

                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 72.dp, bottom = bottomOverlayPadding),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ShortsReelsAuthorRow(baseNote, accountViewModel, nav)
                    ShortsReelsExpandableCaption(
                        event = event,
                        accountViewModel = accountViewModel,
                    )
                    LoadAndDisplayZapraiser(
                        baseNote = baseNote,
                        showReactionDetail = false,
                        wantsToSeeReactions = remember { mutableStateOf(false) },
                        accountViewModel = accountViewModel,
                    )
                }

                PersistentMuteButton(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = bottomOverlayPadding)
                            .then(VolumeBottomIconSize),
                )
            }
        }
    } else {
        val ratio = reelMeta.dim?.aspectRatio() ?: MediaAspectRatioCache.get(reelMeta.url)
        val overlayAuthorOnVideo = (ratio ?: 1f) <= 0.62f

        Column(
            modifier = modifier.fillMaxWidth(),
        ) {
            if (!overlayAuthorOnVideo) {
                UserCardHeader(baseNote, accountViewModel, nav)
            }
            Box {
                VideoCardImage(baseNote, event, reelMeta, backgroundColor, accountViewModel, onTapAction = onTapAction)
                if (overlayAuthorOnVideo) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.55f),
                                        1f to Color.Transparent,
                                    ),
                                ),
                    ) {
                        UserCardHeader(baseNote, accountViewModel, nav, overlay = true)
                    }
                }
            }
            ReactionsRow(
                baseNote = baseNote,
                showReactionDetail = true,
                addPadding = true,
                editState = null,
                accountViewModel = accountViewModel,
                nav = nav,
                instagramStyle = true,
            )
            VideoCardCaption(event, accountViewModel)
        }
    }
}

@Composable
private fun ShortsReelsAuthorRow(
    baseNote: Note,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    val route = routeFor(baseNote, accountViewModel.account)
                    if (route != null) {
                        nav.nav(route)
                    }
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NoteAuthorPicture(
            baseNote = baseNote,
            size = Size35dp,
            accountViewModel = accountViewModel,
            nav = nav,
        )

        NoteUsernameDisplay(
            baseNote = baseNote,
            weight = Modifier.weight(1f, fill = false).widthIn(max = 220.dp),
            textColor = ReelsOverlayWhite,
            accountViewModel = accountViewModel,
        )

        val createdAt = baseNote.createdAt()
        if (createdAt != null) {
            ToggleableTimeAgoText(
                timestamp = createdAt,
                style = TimeAgoStyle.Dotted,
                color = ReelsOverlayWhite.copy(alpha = 0.85f),
            )
        }

        MoreOptionsButton(
            baseNote = baseNote,
            editState = null,
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }
}

@Composable
private fun ShortsReelsExpandableCaption(
    event: Event,
    accountViewModel: AccountViewModel,
) {
    FeedCardCaption(
        title = (event as? VideoEvent)?.title(),
        content = event.content,
        id = event.id,
        accountViewModel = accountViewModel,
        modifier = Modifier.fillMaxWidth(),
        overlay = true,
    )
}

@Composable
private fun VideoCardImage(
    note: Note,
    event: Event,
    reelMeta: com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.ReelVideoMeta,
    backgroundColor: MutableState<Color>,
    accountViewModel: AccountViewModel,
    contentScale: ContentScale = ContentScale.FillWidth,
    modifier: Modifier = Modifier,
    showControls: Boolean = true,
    reelsGestures: Boolean = false,
    startUnmuted: Boolean = false,
    onDoubleTapAction: (() -> Unit)? = null,
    onTapAction: (() -> Unit)? = null,
) {
    val isSensitive = remember(note) { event.isSensitiveOrNSFW() }
    val reasons = remember(note) { collectContentWarningReasons(event) }

    val content by
        remember(note.idHex, reelMeta.url) {
            val description = reelMeta.description ?: event.content.ifEmpty { null }
            mutableStateOf<BaseMediaContent>(
                MediaUrlVideo(
                    url = reelMeta.url,
                    description = description,
                    hash = reelMeta.hash,
                    blurhash = reelMeta.blurhash,
                    dim = reelMeta.dim,
                    uri = note.toNostrUri(),
                    authorName = note.author?.toBestDisplayName(),
                    mimeType = reelMeta.mimeType,
                    thumbhash = reelMeta.thumbhash,
                    artworkUri = reelMeta.artworkUri,
                ),
            )
        }

    val ratio = reelMeta.dim?.aspectRatio() ?: MediaAspectRatioCache.get(reelMeta.url)

    ContentWarningGate(
        isSensitive = isSensitive,
        reasons = reasons,
        preloadUrls = emptyList(),
        accountViewModel = accountViewModel,
        modifier = modifier.then(mediaSizingModifier(ratio, contentScale)),
        backdrop = (reelMeta.thumbhash ?: reelMeta.blurhash)?.let { { BlurhashBackdrop(reelMeta.blurhash, content.description, reelMeta.thumbhash) } },
    ) {
        ZoomableContentView(
            content = content,
            roundedCorner = false,
            contentScale = contentScale,
            accountViewModel = accountViewModel,
            showControls = showControls,
            reelsGestures = reelsGestures,
            startUnmuted = startUnmuted,
            onDoubleTapAction = onDoubleTapAction,
            onTapAction = onTapAction,
        )
    }
}

@Composable
internal fun VideoCardCaption(
    event: Event,
    accountViewModel: AccountViewModel,
) {
    FeedCardCaption(
        title = (event as? VideoEvent)?.title(),
        content = event.content,
        id = event.id,
        accountViewModel = accountViewModel,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
