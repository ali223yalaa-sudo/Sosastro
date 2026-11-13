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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.commons.viewmodels.GIFT_BURST_MS
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGift
import kotlinx.coroutines.delay

/**
 * Full-screen gift playback overlay — the Compose analogue of
 * TUILiveKit's `GiftPlayView`. A transparent layer that sits above
 * the stage and below the interactive controls; it observes the
 * room's gift stream and renders one animated burst per gift.
 *
 * Bursts queue rather than overlap: a burst is `GIFT_BURST_MS` long,
 * and the queue drains sequentially so two gifts sent at the same
 * instant each get their own moment on screen (TRTC plays SVGA
 * clips the same way — one at a time, in order).
 */
@Composable
fun RoomGiftOverlay(
    giftsByTarget: Map<String, List<RoomGift>>,
    modifier: Modifier = Modifier,
) {
    // Flatten to a stable, arrival-ordered queue. Dedup by event id
    // (the aggregator already does, but LocalCache re-emits the full
    // list so re-composition can hand us the same gift twice).
    val queue =
        remember(giftsByTarget) {
            giftsByTarget.values
                .flatten()
                .distinctBy { it.eventId }
                .sortedBy { it.createdAtSec }
        }

    // Track which event ids have already been played so a re-emit
    // doesn't replay an old burst. Bounded by the aggregator window.
    var played by remember { mutableStateOf(emptySet<String>()) }
    var current by remember { mutableStateOf<RoomGift?>(null) }

    LaunchedEffect(queue) {
        val pending = queue.filter { it.eventId !in played }
        for (gift in pending) {
            current = gift
            played = played + gift.eventId
            delay(GIFT_BURST_MS)
        }
        current = null
    }

    current?.let { gift ->
        key(gift.eventId) {
            GiftBurst(gift = gift, modifier = modifier)
        }
    }
}

@Composable
private fun GiftBurst(
    gift: RoomGift,
    modifier: Modifier = Modifier,
) {
    // One-shot rise + scale + fade. Same manual-progress shape the
    // reaction overlay uses so both feel like the same animation
    // vocabulary.
    val progress = remember(gift.eventId) { Animatable(0f) }
    LaunchedEffect(gift.eventId) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = GIFT_BURST_MS.toInt(), easing = FastOutSlowInEasing),
        )
    }
    val p = progress.value
    // Hold full opacity for the first ~60% of the burst, then fade.
    val fade = if (p < 0.6f) 1f else ((1f - p) / 0.4f).coerceIn(0f, 1f)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .graphicsLayer {
                        translationY = -p * 120.dp.toPx()
                        val s = 0.7f + (1f - (p * 2f).coerceAtMost(1f)) * 0.6f
                        scaleX = s
                        scaleY = s
                    }.alpha(fade),
        ) {
            Text(
                text = gift.emoji,
                fontSize = (64 + gift.quantity.coerceAtMost(9) * 2).sp,
            )
            if (gift.quantity > 1) {
                Text(
                    text = "×${gift.quantity}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(GiftGoldDeep.copy(alpha = 0.9f))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Compact always-visible ticker of the newest gifts, drawn in a
 * corner of the room. Complements the full-screen [RoomGiftOverlay]:
 * the burst is transient, this row keeps a short "who sent what"
 * history legible while the room is busy.
 */
@Composable
fun RoomGiftTicker(
    giftsByTarget: Map<String, List<RoomGift>>,
    nameFor: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val recent =
        remember(giftsByTarget) {
            giftsByTarget.values
                .flatten()
                .sortedByDescending { it.createdAtSec }
                .take(3)
        }
    if (recent.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        recent.forEach { gift ->
            key(gift.eventId) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(text = gift.emoji, fontSize = 16.sp)
                    Text(
                        text =
                            if (gift.quantity > 1) {
                                "${nameFor(gift.sourcePubkey)} ×${gift.quantity}"
                            } else {
                                nameFor(gift.sourcePubkey)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
