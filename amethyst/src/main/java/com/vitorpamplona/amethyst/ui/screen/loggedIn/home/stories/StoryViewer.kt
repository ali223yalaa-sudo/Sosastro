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

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.playback.composable.VideoView
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.event.observeNoteReactions
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.routeCommentOnNote
import com.vitorpamplona.amethyst.ui.navigation.routes.routeFor
import com.vitorpamplona.amethyst.ui.note.ClickableUserPicture
import com.vitorpamplona.amethyst.ui.note.LikeReaction
import com.vitorpamplona.amethyst.ui.note.ReplyReaction
import com.vitorpamplona.amethyst.ui.note.UsernameDisplay
import com.vitorpamplona.amethyst.ui.note.timeAgoNoDot
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.Font14SP
import com.vitorpamplona.amethyst.ui.theme.Size24Modifier
import com.vitorpamplona.amethyst.ui.theme.Size24dp
import com.vitorpamplona.quartz.experimental.ephemeralStory.EphemeralStoryEvent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val IMAGE_STORY_MS = 8_000L
private const val VIDEO_STORY_MS = 15_000L
private const val STORY_ANIM_MS = 180
private const val AUTHOR_ANIM_MS = 420

@Composable
fun EphemeralStoryViewer(
    rings: ImmutableList<StoryAuthorRing>,
    startAuthorIndex: Int,
    accountViewModel: AccountViewModel,
    nav: INav,
    onDismiss: () -> Unit,
) {
    if (rings.isEmpty()) {
        onDismiss()
        return
    }

    val scope = rememberCoroutineScope()
    val layoutDir = LocalLayoutDirection.current
    val isRtl = layoutDir == LayoutDirection.Rtl

    var authorIndex by remember { mutableIntStateOf(startAuthorIndex.coerceIn(0, rings.lastIndex)) }
    var storyIndex by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    var mediaReady by remember(authorIndex, storyIndex) { mutableStateOf(false) }
    var storySlideForward by remember { mutableStateOf(true) }
    var authorSlideForward by remember { mutableStateOf(true) }
    var showViewers by remember { mutableStateOf(false) }
    var draggingDown by remember { mutableStateOf(false) }
    val dismissY = remember { Animatable(0f) }

    val ring = rings[authorIndex]
    val stories = ring.stories
    val currentNote = stories.getOrNull(storyIndex)
    val currentEvent = currentNote?.event as? EphemeralStoryEvent
    val myHex = accountViewModel.userProfile().pubkeyHex
    val isMine = ring.isMine || ring.authorHex == myHex
    val context = LocalContext.current
    val paused = holding || showViewers || draggingDown

    fun markAuthorSeen() {
        val latest = ring.latestCreatedAt
        if (latest > 0) {
            accountViewModel.loadAndMarkAsRead(ephemeralStorySeenRoute(ring.authorHex), latest)
        }
    }

    fun goNextStory() {
        if (storyIndex < stories.lastIndex) {
            storySlideForward = true
            storyIndex += 1
        } else {
            markAuthorSeen()
            if (authorIndex < rings.lastIndex) {
                authorSlideForward = true
                authorIndex += 1
                storyIndex = 0
            } else {
                onDismiss()
            }
        }
    }

    fun goPrevStory() {
        if (storyIndex > 0) {
            storySlideForward = false
            storyIndex -= 1
        } else if (authorIndex > 0) {
            markAuthorSeen()
            authorSlideForward = false
            val prevLast = (rings[authorIndex - 1].stories.size - 1).coerceAtLeast(0)
            authorIndex -= 1
            storyIndex = prevLast
        } else {
            onDismiss()
        }
    }

    fun goNextAuthor() {
        markAuthorSeen()
        if (authorIndex < rings.lastIndex) {
            authorSlideForward = true
            authorIndex += 1
            storyIndex = 0
        } else {
            onDismiss()
        }
    }

    fun goPrevAuthor() {
        if (authorIndex > 0) {
            markAuthorSeen()
            authorSlideForward = false
            val prevLast = (rings[authorIndex - 1].stories.size - 1).coerceAtLeast(0)
            authorIndex -= 1
            storyIndex = prevLast
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(authorIndex, stories.size) {
        if (storyIndex >= stories.size) {
            storyIndex = (stories.size - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(currentNote?.idHex, mediaReady, isMine) {
        val note = currentNote ?: return@LaunchedEffect
        if (!mediaReady || isMine) return@LaunchedEffect
        accountViewModel.markEphemeralStoryViewed(note)
    }

    val latestPaused by rememberUpdatedState(paused)
    val latestGoNext by rememberUpdatedState { goNextStory() }

    LaunchedEffect(authorIndex, storyIndex, mediaReady, currentNote?.idHex) {
        progress = 0f
        if (currentEvent == null || !mediaReady) return@LaunchedEffect
        val duration = if (currentEvent.isVideo()) VIDEO_STORY_MS else IMAGE_STORY_MS
        val start = SystemClock.elapsedRealtime()
        var pausedAccum = 0L
        var pauseStarted = 0L
        while (isActive) {
            if (latestPaused) {
                if (pauseStarted == 0L) pauseStarted = SystemClock.elapsedRealtime()
                delay(16)
                continue
            }
            if (pauseStarted != 0L) {
                pausedAccum += SystemClock.elapsedRealtime() - pauseStarted
                pauseStarted = 0L
            }
            val elapsed = SystemClock.elapsedRealtime() - start - pausedAccum
            val p = (elapsed / duration.toFloat()).coerceIn(0f, 1f)
            progress = p
            if (p >= 1f) {
                latestGoNext()
                return@LaunchedEffect
            }
            delay(16)
        }
    }

    Dialog(
        onDismissRequest = {
            markAuthorSeen()
            onDismiss()
        },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .graphicsLayer {
                        val y = dismissY.value
                        translationY = y
                        val p = (y / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val scale = 1f - p * 0.06f
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - p * 0.28f
                    },
        ) {
            AnimatedContent(
                targetState = authorIndex,
                transitionSpec = {
                    val forward = authorSlideForward
                    val enterX = if (forward) 1 else -1
                    val exitX = if (forward) -1 else 1
                    (
                        slideInHorizontally(tween(AUTHOR_ANIM_MS, easing = FastOutSlowInEasing)) { full -> enterX * full } +
                            scaleIn(tween(AUTHOR_ANIM_MS), initialScale = 0.88f) +
                            fadeIn(tween(AUTHOR_ANIM_MS))
                    ) togetherWith (
                        slideOutHorizontally(tween(AUTHOR_ANIM_MS, easing = FastOutSlowInEasing)) { full -> exitX * full / 2 } +
                            scaleOut(tween(AUTHOR_ANIM_MS), targetScale = 0.92f) +
                            fadeOut(tween(AUTHOR_ANIM_MS))
                    )
                },
                label = "storyAuthor",
                modifier = Modifier.fillMaxSize(),
            ) { pageAuthor ->
                val pageRing = rings.getOrNull(pageAuthor) ?: return@AnimatedContent
                val pageStories = pageRing.stories

                AnimatedContent(
                    targetState = if (pageAuthor == authorIndex) storyIndex else 0,
                    transitionSpec = {
                        val forward = storySlideForward
                        val dir = if (forward) 1 else -1
                        (
                            fadeIn(tween(STORY_ANIM_MS)) +
                                slideInHorizontally(tween(STORY_ANIM_MS)) { dir * it / 10 }
                        ) togetherWith (
                            fadeOut(tween(STORY_ANIM_MS)) +
                                slideOutHorizontally(tween(STORY_ANIM_MS)) { -dir * it / 10 }
                        )
                    },
                    label = "storySegment",
                    modifier = Modifier.fillMaxSize(),
                ) { pageStory ->
                    val note = pageStories.getOrNull(pageStory)
                    val event = note?.event as? EphemeralStoryEvent
                    if (note != null && event != null) {
                        StoryMediaContent(
                            note = note,
                            event = event,
                            accountViewModel = accountViewModel,
                            onMediaReady = {
                                if (pageAuthor == authorIndex && pageStory == storyIndex) {
                                    mediaReady = true
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            var dragAcc by remember { mutableFloatStateOf(0f) }
            var dragAxis by remember { mutableStateOf<Boolean?>(null) }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(authorIndex, storyIndex, isRtl) {
                            detectTapGestures(
                                onPress = {
                                    holding = true
                                    tryAwaitRelease()
                                    holding = false
                                },
                                onTap = { offset ->
                                    val w = size.width.toFloat()
                                    val backZone = w * 0.33f
                                    val tappedBack = if (isRtl) offset.x > w - backZone else offset.x < backZone
                                    if (tappedBack) goPrevStory() else goNextStory()
                                },
                            )
                        }.pointerInput(authorIndex, isRtl) {
                            detectDragGestures(
                                onDragStart = {
                                    dragAcc = 0f
                                    dragAxis = null
                                    draggingDown = false
                                },
                                onDragEnd = {
                                    val vertical = dragAxis == true
                                    val y = dismissY.value
                                    val threshold = 140.dp.toPx()
                                    if (vertical) {
                                        scope.launch {
                                            if (y > threshold) {
                                                dismissY.animateTo(
                                                    size.height.toFloat(),
                                                    tween(340, easing = FastOutSlowInEasing),
                                                )
                                                markAuthorSeen()
                                                onDismiss()
                                            } else {
                                                dismissY.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
                                            }
                                            draggingDown = false
                                        }
                                    } else {
                                        draggingDown = false
                                        val goNextSwipe = if (isRtl) dragAcc > 80f else dragAcc < -80f
                                        val goPrevSwipe = if (isRtl) dragAcc < -80f else dragAcc > 80f
                                        when {
                                            goNextSwipe -> goNextAuthor()
                                            goPrevSwipe -> goPrevAuthor()
                                        }
                                    }
                                    dragAcc = 0f
                                    dragAxis = null
                                },
                                onDragCancel = {
                                    draggingDown = false
                                    scope.launch { dismissY.animateTo(0f, tween(240)) }
                                    dragAcc = 0f
                                    dragAxis = null
                                },
                                onDrag = { change, amount ->
                                    if (dragAxis == null) {
                                        dragAxis = abs(amount.y) > abs(amount.x)
                                    }
                                    if (dragAxis == true) {
                                        draggingDown = true
                                        change.consume()
                                        val next = (dismissY.value + amount.y).coerceAtLeast(0f)
                                        scope.launch { dismissY.snapTo(next) }
                                    } else {
                                        dragAcc += amount.x
                                    }
                                },
                            )
                        },
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                StoryProgressRow(
                    count = stories.size,
                    currentIndex = storyIndex,
                    currentProgress = if (mediaReady) progress else 0f,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ring.author?.let { user ->
                        val openProfile = {
                            markAuthorSeen()
                            onDismiss()
                            nav.nav(routeFor(user))
                        }
                        ClickableUserPicture(
                            baseUser = user,
                            size = 32.dp,
                            accountViewModel = accountViewModel,
                            onClick = { openProfile() },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .clickable(onClick = openProfile),
                        ) {
                            UsernameDisplay(
                                baseUser = user,
                                fontWeight = FontWeight.SemiBold,
                                textColor = Color.White,
                                accountViewModel = accountViewModel,
                            )
                            val createdAt = currentNote?.createdAt() ?: currentEvent?.createdAt
                            if (createdAt != null) {
                                Text(
                                    text = timeAgoNoDot(createdAt, context).trim(),
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    } ?: Spacer(Modifier.weight(1f))

                    IconButton(onClick = {
                        markAuthorSeen()
                        onDismiss()
                    }) {
                        Icon(
                            symbol = MaterialSymbols.Close,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                }

                currentEvent?.content?.takeIf { it.isNotBlank() }?.let { caption ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = caption,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            if (isMine && currentNote != null) {
                StoryViewersFooter(
                    note = currentNote,
                    accountViewModel = accountViewModel,
                    onClick = { showViewers = true },
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(16.dp),
                )
            }

            if (!isMine && currentNote != null) {
                StoryInteractBar(
                    note = currentNote,
                    accountViewModel = accountViewModel,
                    nav = nav,
                    onReply = { nav.nav { routeCommentOnNote(currentNote, accountViewModel.account) } },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        if (showViewers && currentNote != null) {
            StoryViewersBottomSheet(
                note = currentNote,
                accountViewModel = accountViewModel,
                nav = nav,
                onDismiss = { showViewers = false },
            )
        }
    }
}

@Composable
private fun StoryInteractBar(
    note: Note,
    accountViewModel: AccountViewModel,
    nav: INav,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .weight(1f)
                    .clickable(onClick = onReply)
                    .padding(vertical = 8.dp, horizontal = 8.dp),
        ) {
            Text(
                text = stringRes(R.string.story_reply_hint),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LikeReaction(
                baseNote = note,
                grayTint = Color.White,
                accountViewModel = accountViewModel,
                nav = nav,
                iconSize = Size24dp,
                heartSizeModifier = Size24Modifier,
                iconFontSize = Font14SP,
                showCounter = true,
            )
            Spacer(Modifier.width(8.dp))
            ReplyReaction(
                baseNote = note,
                grayTint = Color.White,
                accountViewModel = accountViewModel,
                showCounter = true,
                iconSizeModifier = Size24Modifier,
                onPress = onReply,
            )
        }
    }
}

@Composable
private fun StoryViewersFooter(
    note: Note,
    accountViewModel: AccountViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reactionsState by observeNoteReactions(note, accountViewModel)
    val count =
        remember(reactionsState, note.reactions) {
            note.reactions[STORY_VIEW_REACTION]
                ?.mapNotNull { it.author?.pubkeyHex }
                ?.toSet()
                ?.size
                ?: 0
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            symbol = MaterialSymbols.Visibility,
            contentDescription = stringRes(R.string.story_viewers),
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringRes(R.string.story_views_count, count),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StoryProgressRow(
    count: Int,
    currentIndex: Int,
    currentProgress: Float,
) {
    if (count <= 0) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(count) { i ->
            val value =
                when {
                    i < currentIndex -> 1f
                    i == currentIndex -> currentProgress.coerceIn(0f, 1f)
                    else -> 0f
                }
            LinearProgressIndicator(
                progress = { value },
                modifier =
                    Modifier
                        .weight(1f)
                        .height(2.5.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
private fun StoryMediaContent(
    note: Note,
    event: EphemeralStoryEvent,
    accountViewModel: AccountViewModel,
    onMediaReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = event.url() ?: return
    val mime = event.mimeType()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (event.isVideo()) {
            VideoView(
                videoUri = url,
                mimeType = mime,
                title = event.content.ifBlank { null },
                artworkUri = null,
                authorName = null,
                roundedCorner = false,
                contentScale = ContentScale.Fit,
                waveform = null,
                accountViewModel = accountViewModel,
                nostrUriCallback = null,
                showControls = false,
                alwaysShowVideo = true,
                onPlaybackReady = onMediaReady,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = event.content,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success,
                        is AsyncImagePainter.State.Error,
                        -> onMediaReady()
                        else -> Unit
                    }
                },
            )
        }
    }
}
