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
package com.vitorpamplona.amethyst.ui.note.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vitorpamplona.amethyst.commons.emojicoder.EmojiCoder
import com.vitorpamplona.amethyst.model.User
import com.vitorpamplona.amethyst.ui.components.InLineIconRenderer
import com.vitorpamplona.amethyst.ui.feeds.LocalFeedListIsScrolling
import com.vitorpamplona.amethyst.ui.note.LikedIcon
import com.vitorpamplona.amethyst.ui.note.ObserveAndDrawInnerUserPicture
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.quartz.nip30CustomEmoji.CustomEmoji
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import kotlin.math.abs

private val InstagramLikeRed = Color(0xFFED4956)

@Immutable
data class FriendReactionChip(
    val pubkeyHex: String,
    val user: User,
    val reactionType: String,
    val createdAt: Long,
)

/**
 * Wraps a decoration so it floats [liftDp] above its slot and can be grabbed. Dragging moves it
 * freely; letting go past [dismissThresholdDp] fades it out and it stays gone, otherwise it
 * springs back. The offset is read inside [graphicsLayer], so a drag repaints a layer without
 * recomposing the note, and only a real drag is consumed — a plain tap still reaches the post.
 */
@Composable
fun FloatingDismissible(
    liftDp: Float,
    modifier: Modifier = Modifier,
    dismissThresholdDp: Float = 56f,
    content: @Composable () -> Unit,
) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return
    val dragX = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { dismissThresholdDp.dp.toPx() }
    Box(
        modifier =
            modifier
                // A real offset (not a zero-size overlay) so the group keeps a hit area and can
                // be grabbed; the lift parks it over the media above the reactions row.
                .offset(y = (-liftDp).dp)
                .graphicsLayer {
                    translationX = dragX.value
                    this.alpha = alpha.value
                }.pointerInput(Unit) {
                    // Horizontal only: a vertical drag must fall through to the feed scroll.
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch { dragX.snapTo(dragX.value + amount) }
                        },
                        onDragEnd = {
                            if (abs(dragX.value) > thresholdPx) {
                                scope.launch {
                                    alpha.animateTo(0f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
                                    dismissed = true
                                }
                            } else {
                                scope.launch { dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                            }
                        },
                        onDragCancel = {
                            scope.launch { dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                        },
                    )
                },
    ) {
        content()
    }
}

private const val AXIS_UNDECIDED = 0
private const val AXIS_HORIZONTAL = 1
private const val AXIS_VERTICAL = 2

/** A vertical drag has to travel this far before we treat it as grabbing the chip rather than
 *  flicking the reels pager — well past the touch slop, so a normal flick never reaches it. */
private val ReelsVerticalGrabPx = 24.dp

/**
 * Reels variant of the floating reaction group. Reels play full-screen video, so the feed's bare
 * pile of avatars sitting straight on the frame reads as clutter. Here the group lives in a single
 * frosted-glass pill — soft shadow, hairline highlight, translucent surface — so it reads as one
 * deliberate overlay instead of loose stickers on the video.
 */
@Composable
fun ReelsFloatingGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier =
            modifier
                .shadow(elevation = 10.dp, shape = shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .border(0.7.dp, Color.White.copy(alpha = 0.18f), shape)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * Grab-and-flick for a reels overlay. The reels feed only scrolls vertically, so a horizontal
 * drag is consumed outright — there is no other horizontal gesture to steal. A vertical drag is
 * consumed only after it passes [ReelsVerticalGrabPx], by which point a pager flick has already
 * been handed to the list, while a deliberate grab-and-drag on the chip moves the chip instead.
 * The settle/dismiss decision is made by the caller from the accumulated offset.
 */
fun Modifier.reelsDraggable(
    onDelta: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        var origin = Offset.Zero
        var axis = AXIS_UNDECIDED
        detectDragGestures(
            onDragStart = { offset ->
                origin = offset
                axis = AXIS_UNDECIDED
            },
            onDragEnd = { onEnd() },
            onDragCancel = { onCancel() },
            onDrag = { change, delta ->
                if (axis == AXIS_UNDECIDED) {
                    val d = change.position - origin
                    val ax = abs(d.x)
                    val ay = abs(d.y)
                    axis =
                        when {
                            ax > ay -> AXIS_HORIZONTAL
                            ay > ax * 1.5f -> AXIS_VERTICAL
                            else -> AXIS_UNDECIDED
                        }
                }
                when (axis) {
                    AXIS_HORIZONTAL -> {
                        change.consume()
                        onDelta(delta)
                    }
                    AXIS_VERTICAL -> {
                        if (abs((change.position - origin).y) > ReelsVerticalGrabPx.toPx()) {
                            change.consume()
                            onDelta(delta)
                        }
                    }
                }
            },
        )
    }

/**
 * Wraps a reels floating group so it can be grabbed and flicked away in any direction. The drag
 * offset and alpha are read inside [graphicsLayer], so a drag repaints a layer without
 * recomposing the reel, and the group springs back to its slot unless it is flicked past
 * [dismissThresholdDp], in which case it fades out and stays gone.
 */
@Composable
fun ReelsFloatingDismissible(
    modifier: Modifier = Modifier,
    dismissThresholdDp: Float = 56f,
    content: @Composable BoxScope.() -> Unit,
) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return
    var drag by remember { mutableStateOf(Offset.Zero) }
    val alpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { dismissThresholdDp.dp.toPx() }

    fun settle() {
        if (drag.getDistance() > thresholdPx) {
            scope.launch {
                alpha.animateTo(0f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
                dismissed = true
            }
        } else {
            scope.launch {
                Animatable(drag, Offset.VectorConverter).animateTo(
                    targetValue = Offset.Zero,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ) { drag = value }
            }
        }
    }

    Box(
        modifier =
            modifier
                .graphicsLayer {
                    translationX = drag.x
                    translationY = drag.y
                    this.alpha = alpha.value
                }.reelsDraggable(
                    onDelta = { drag += it },
                    onEnd = { settle() },
                    onCancel = { settle() },
                ),
        content = content,
    )
}

/**
 * A barely-there vertical drift, phase-shifted per [phaseIndex] so a row of avatars breathes
 * out of sync. The animated value is only read inside [graphicsLayer], so it repaints the layer
 * without triggering recomposition — a feed of these costs nothing extra. When [scrolling] is
 * true the animation is skipped entirely so flinging the list stays cheap.
 */
@Composable
private fun Modifier.subtleFloat(
    phaseIndex: Int,
    scrolling: Boolean,
    amplitudeDp: Float = 1.6f,
): Modifier {
    if (scrolling) return this
    val transition = rememberInfiniteTransition(label = "friendReactionFloat")
    val offset by
        transition.animateFloat(
            initialValue = -amplitudeDp,
            targetValue = amplitudeDp,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 2600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "friendReactionFloatOffset",
        )
    val density = LocalDensity.current
    return graphicsLayer {
        // Half the avatars ride the opposite phase so neighbours don't move in lockstep.
        val signedDp = if (phaseIndex % 2 == 0) offset else -offset
        translationY = with(density) { signedDp.dp.toPx() }
    }
}

@Composable
fun FriendReactionFacepile(
    chips: List<FriendReactionChip>,
    accountViewModel: AccountViewModel,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 20.dp,
    floating: Boolean = false,
) {
    if (chips.isEmpty()) return
    val scrolling = if (floating) LocalFeedListIsScrolling.current.value else false
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((-7).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val last = chips.lastIndex
        chips.forEachIndexed { index, chip ->
            FriendReactionAvatar(
                user = chip.user,
                reactionType = chip.reactionType,
                accountViewModel = accountViewModel,
                modifier =
                    Modifier
                        .zIndex((last - index + 1).toFloat())
                        .then(if (floating) Modifier.subtleFloat(index, scrolling) else Modifier),
                size = avatarSize,
            )
        }
    }
}

@Composable
fun FriendReactionAvatar(
    user: User,
    reactionType: String,
    accountViewModel: AccountViewModel,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val ring = MaterialTheme.colorScheme.surface
    val badge = (size * 0.58f).coerceIn(11.dp, 14.dp)
    Box(modifier = modifier.size(size + 6.dp)) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .size(size)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .border(1.5.dp, ring, CircleShape),
        ) {
            ObserveAndDrawInnerUserPicture(
                user = user,
                size = size,
                accountViewModel = accountViewModel,
            )
        }
        ReactionStickerBadge(
            reactionType = reactionType,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp),
            size = badge,
        )
    }
}

@Composable
private fun ReactionStickerBadge(
    reactionType: String,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    val ring = MaterialTheme.colorScheme.surface
    Box(
        modifier =
            modifier
                .size(size)
                .shadow(1.dp, CircleShape)
                .clip(CircleShape)
                .background(ring)
                .border(1.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            reactionType.isEmpty() || reactionType == "+" -> {
                LikedIcon(Modifier.size(size * 0.72f), tint = InstagramLikeRed)
            }
            reactionType == "-" -> {
                Text(text = "\uD83D\uDC4E", fontSize = 7.sp, maxLines = 1)
            }
            reactionType.startsWith(":") -> {
                val renderable =
                    remember(reactionType) {
                        persistentListOf(
                            CustomEmoji.ImageUrlType(reactionType.removePrefix(":").substringAfter(":")),
                        )
                    }
                InLineIconRenderer(
                    renderable,
                    style = SpanStyle(color = Color.White),
                    fontSize = 8.sp,
                    maxLines = 1,
                )
            }
            EmojiCoder.isCoded(reactionType) -> {
                Text(text = "✦", color = InstagramLikeRed, fontSize = 7.sp, maxLines = 1)
            }
            else -> {
                Text(text = reactionType, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun GiftLaunchBurst(
    emoji: String,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    val progress = remember(emoji) { Animatable(0f) }
    LaunchedEffect(emoji) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        )
        onFinished()
    }
    val p = progress.value
    val fade = if (p < 0.15f) p / 0.15f else ((1f - p) / 0.85f).coerceIn(0f, 1f)
    Text(
        text = emoji,
        fontSize = 36.sp,
        modifier =
            modifier
                .zIndex(3f)
                .graphicsLayer {
                    translationY = -p * 96.dp.toPx()
                    rotationZ = p * 18f
                    val scale = 0.7f + (1f - p) * 0.55f
                    scaleX = scale
                    scaleY = scale
                    alpha = fade
                },
    )
}

@Composable
fun ReceivedGiftTrail(
    emojis: List<String>,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
) {
    if (emojis.isEmpty()) return
    val outline = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val surface = MaterialTheme.colorScheme.surface
    val scrolling = if (floating) LocalFeedListIsScrolling.current.value else false
    Row(
        modifier = modifier.offset(x = (-2).dp),
        horizontalArrangement = Arrangement.spacedBy((-7).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        emojis.take(4).forEachIndexed { index, emoji ->
            Box(
                modifier =
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(surface)
                        .border(0.6.dp, outline, CircleShape)
                        .then(if (floating) Modifier.subtleFloat(index, scrolling) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, fontSize = 10.sp)
            }
        }
    }
}
