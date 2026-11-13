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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGift
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGiftCatalog
import com.vitorpamplona.amethyst.commons.viewmodels.giftBurstMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen gift playback overlay — the Compose analogue of
 * TUILiveKit's `GiftPlayView`. A transparent layer that sits above
 * the stage and below the interactive controls; it observes the
 * room's gift stream and renders one animated burst per gift.
 *
 * The treatment scales with the gift's [RoomGift.tier], mirroring how
 * a live-streaming app maps a gift's price band to a bigger SVGA clip:
 *
 *   * 1 standard — a small emoji rise.
 *   * 2 premium  — a bigger rise inside a gold sparkle ring.
 *   * 3 luxe     — a full-screen cinematic takeover: dark scrim,
 *     rotating light rays, confetti and a big floating emblem.
 *   * 4 legendary — a mega takeover: the luxe layers plus a screen
 *     flash, expanding shock rings and a sender → receiver marquee.
 *
 * Bursts queue rather than overlap: each plays for
 * [giftBurstMillis] before the next starts, so two gifts sent at the
 * same instant each get their own moment on screen.
 */
@Composable
fun RoomGiftOverlay(
    giftsByTarget: Map<String, List<RoomGift>>,
    nameFor: (String) -> String,
    modifier: Modifier = Modifier,
) {
    // Flatten to a stable, arrival-ordered list. Dedup by event id
    // (the aggregator already does, but LocalCache re-emits the full
    // list so re-composition can hand us the same gift twice).
    val queue =
        remember(giftsByTarget) {
            giftsByTarget.values
                .flatten()
                .distinctBy { it.eventId }
                .sortedBy { it.createdAtSec }
        }

    var current by remember { mutableStateOf<RoomGift?>(null) }

    // SVGA assets play to completion and then signal here; gifts
    // without an asset fall back to a timed burst.
    var svgaDone by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }

    // Single-slot queue fed by a producer that re-scans the (re-emitted)
    // gift list on every change. A Channel is essential here: keying the
    // consumer on the list would cancel and restart the *in-flight*
    // burst each time a new gift lands (LocalCache re-emits the full
    // list on every mutation), so a busy room would only ever show the
    // first frame of the newest gift. The consumer runs once for the
    // overlay's lifetime and drains whatever the producer enqueues.
    val pending = remember { Channel<RoomGift>(Channel.UNLIMITED) }
    val enqueued = remember { mutableSetOf<String>() }
    LaunchedEffect(queue) {
        for (gift in queue) {
            if (enqueued.add(gift.eventId)) pending.trySend(gift)
        }
    }
    LaunchedEffect(Unit) {
        for (gift in pending) {
            current = gift
            if (RoomGiftCatalog.byId(gift.skuId)?.svga != null) {
                val done = CompletableDeferred<Unit>()
                svgaDone = done
                // Safety net: never hang the queue if the player stalls.
                withTimeoutOrNull(giftBurstMillis(gift.tier) + 4_000L) { done.await() }
                svgaDone = null
            } else {
                delay(giftBurstMillis(gift.tier))
            }
            current = null
        }
    }

    current?.let { gift ->
        key(gift.eventId) {
            GiftBurst(
                gift = gift,
                senderName = nameFor(gift.sourcePubkey),
                receiverName = gift.targetPubkey?.let(nameFor),
                onSvgaFinished = { svgaDone?.complete(Unit) },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun GiftBurst(
    gift: RoomGift,
    senderName: String,
    receiverName: String?,
    onSvgaFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val svgaAsset = RoomGiftCatalog.byId(gift.skuId)?.svga
    val epic = gift.tier >= 3
    val durationMs = giftBurstMillis(gift.tier).toInt()

    // One-shot 0→1 progress the whole burst is driven from, so every
    // sub-layer animates off the same clock and stays in sync.
    val progress = remember(gift.eventId) { Animatable(0f) }
    LaunchedEffect(gift.eventId) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
        )
    }
    val p = progress.value

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (epic) {
            EpicBackdrop(gift = gift, progress = p)
        }
        if (svgaAsset != null) {
            // Imported SVGA clip plays full-screen; the emoji emblem is
            // suppressed and only the sender→receiver marquee overlays it.
            SvgaGiftPlayer(
                assetPath = svgaAsset,
                onFinished = onSvgaFinished,
                modifier = Modifier.fillMaxSize(),
            )
            if (epic) {
                GiftMarquee(
                    senderName = senderName,
                    receiverName = receiverName,
                    gift = gift,
                    progress = p,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                )
            }
        } else {
            Emblem(gift = gift, senderName = senderName, receiverName = receiverName, epic = epic, progress = p)
        }
    }
}

/** Dark scrim + rotating rays + confetti + (legendary) flash & shock rings. */
@Composable
private fun EpicBackdrop(
    gift: RoomGift,
    progress: Float,
) {
    val legendary = gift.tier >= 4
    // Fade the scrim in fast, hold, then fade out over the last 25%.
    val scrimAlpha =
        when {
            progress < 0.12f -> progress / 0.12f * 0.72f
            progress > 0.75f -> ((1f - progress) / 0.25f).coerceIn(0f, 1f) * 0.72f
            else -> 0.72f
        }
    // Screen flash only for the legendary class, right at the impact.
    val flashAlpha =
        if (legendary) {
            val f = ((progress - 0.08f) / 0.18f).coerceIn(0f, 1f)
            (1f - f) * 0.85f
        } else {
            0f
        }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = size.minDimension

        // Scrim.
        if (scrimAlpha > 0f) {
            drawRect(
                brush =
                    Brush.radialGradient(
                        colors = listOf(GiftGoldDeep.copy(alpha = scrimAlpha), Color.Black.copy(alpha = scrimAlpha)),
                        center = Offset(cx, cy),
                        radius = maxR * 0.9f,
                    ),
            )
        }

        // Rotating light rays.
        val rayAlpha = (scrimAlpha / 0.72f) * 0.5f
        if (rayAlpha > 0.01f) {
            val rotation = progress * 90f
            val rays = 12
            for (i in 0 until rays) {
                val angle = (i * (360f / rays) + rotation) * (PI.toFloat() / 180f)
                val sweep = 5.5f * (PI.toFloat() / 180f)
                val path =
                    Path().apply {
                        moveTo(cx, cy)
                        val r = maxR * 1.2f
                        lineTo(cx + r * cos(angle - sweep), cy + r * sin(angle - sweep))
                        lineTo(cx + r * cos(angle + sweep), cy + r * sin(angle + sweep))
                        close()
                    }
                drawPath(path = path, color = GiftGold.copy(alpha = rayAlpha * 0.35f))
            }
        }

        // Shock rings expanding from the centre (legendary only).
        if (legendary) {
            for (i in 0 until 3) {
                val rp = ((progress - i * 0.12f) / 0.5f).coerceIn(0f, 1f)
                if (rp <= 0f) continue
                drawCircle(
                    color = GiftGold.copy(alpha = (1f - rp) * 0.6f),
                    radius = maxR * 0.5f * rp,
                    center = Offset(cx, cy),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // Confetti — deterministic layout so it doesn't re-randomise
        // on every frame.
        val confettiAlpha = (scrimAlpha / 0.72f) * 0.9f
        if (confettiAlpha > 0.01f) {
            val colors = listOf(GiftGold, Color(0xFFFF8A65), Color(0xFF80DEEA), Color(0xFFF48FB1), Color(0xFFB39DDB))
            val strips = 46
            for (i in 0 until strips) {
                val seed = (i * 2654435761L).toInt()
                val x = ((seed ushr 1) % 1000) / 1000f
                val delay = ((seed ushr 11) % 400) / 1000f
                val fall = ((progress - delay) / 0.7f).coerceIn(0f, 1f)
                if (fall <= 0f) continue
                val drift = sin(fall * 6f + i) * 0.04f
                val px = (x + drift) * size.width
                val py = fall * size.height
                val h = 14f + ((seed ushr 20) % 12)
                drawRect(
                    color = colors[i % colors.size].copy(alpha = confettiAlpha * (1f - fall * 0.4f)),
                    topLeft = Offset(px, py),
                    size = Size(6f, h),
                )
            }
        }

        // White flash (legendary).
        if (flashAlpha > 0.01f) {
            drawRect(color = Color.White.copy(alpha = flashAlpha))
        }
    }
}

/** The floating gift emblem + quantity + names. */
@Composable
private fun Emblem(
    gift: RoomGift,
    senderName: String,
    receiverName: String?,
    epic: Boolean,
    progress: Float,
) {
    // Entrance pop then drift up; fade out over the tail.
    val fade = if (progress < 0.7f) 1f else ((1f - progress) / 0.3f).coerceIn(0f, 1f)
    val pop = (progress / 0.15f).coerceIn(0f, 1f)
    val baseEmoji = if (epic) 130f else 64f
    val emojiSize = baseEmoji + gift.quantity.coerceAtMost(20) * 1.5f

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .alpha(fade),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .graphicsLayer {
                        translationY = -progress * (if (epic) 40.dp.toPx() else 120.dp.toPx())
                        val s = 0.6f + pop * 0.4f + (if (epic) (1f - pop) * 0.15f else 0f)
                        scaleX = s
                        scaleY = s
                    },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = gift.emoji, fontSize = emojiSize.sp)
                if (gift.quantity > 1) {
                    Text(
                        text = "×${gift.quantity}",
                        fontSize = (if (epic) 30 else 18).sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF2A1E00),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(GiftGold)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            // Sender → receiver marquee for the cinematic tiers.
            if (epic) {
                GiftMarquee(
                    senderName = senderName,
                    receiverName = receiverName,
                    gift = gift,
                    progress = progress,
                )
            }
        }
    }
}

/** Sender → receiver name pill shown during a cinematic gift. */
@Composable
private fun GiftMarquee(
    senderName: String,
    receiverName: String?,
    gift: RoomGift,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val fade = if (progress < 0.8f) 1f else ((1f - progress) / 0.2f).coerceIn(0f, 1f)
    val line =
        if (receiverName != null) {
            "$senderName  ➜  $receiverName"
        } else {
            senderName
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .alpha(fade)
                .clip(RoundedCornerShape(50))
                .background(GiftGoldDeep.copy(alpha = 0.9f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text = gift.emoji, fontSize = 18.sp)
        Text(
            text = line,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        if (gift.quantity > 1) {
            Text(
                text = "×${gift.quantity}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF2A1E00),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GiftGold)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
