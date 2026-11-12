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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
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

private val InstagramLikeRed = Color(0xFFED4956)

@Immutable
data class FriendReactionChip(
    val pubkeyHex: String,
    val user: User,
    val reactionType: String,
    val createdAt: Long,
)

/**
 * Measures the content but reports a 0×0 size, placing it [offsetYDp] above its slot. Lets a
 * decoration overlap the note above it without shifting any sibling — the parent Box sizes
 * itself from the real icon, and the decoration draws on top (the reactions slot is drawn last).
 */
fun Modifier.floatingOverlay(offsetYDp: Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val liftPx = offsetYDp.dp.roundToPx()
        layout(0, 0) { placeable.place(0, -liftPx) }
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
