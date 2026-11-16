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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.BuildConfig
import com.vitorpamplona.amethyst.commons.viewmodels.GiftMotif
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGift
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGiftCatalog
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGiftTheme
import com.vitorpamplona.amethyst.commons.viewmodels.giftBurstMillis
import com.vitorpamplona.amethyst.commons.viewmodels.giftThemeFor
import com.vitorpamplona.amethyst.model.privacyOptions.IRoleBasedHttpClientBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shortest time a gift burst stays on screen, even when its SVGA clip is
 * shorter. The authored `gen_*` clips run 3 s; without this floor a burst
 * ended the instant the clip did, so a gift flashed by like an emoji.
 */
private const val MIN_GIFT_HOLD_MS = 4_600L

/**
 * How long to wait for a clip to reach the screen before giving up and
 * starting the burst anyway. Covers a cold-cache download (a dashboard
 * clip is multi-megabyte) and, at the extreme, a clip that never
 * arrives — the burst then falls back to its timed hold rather than
 * stalling the queue behind an empty scene.
 */
private const val READY_TIMEOUT_MS = 8_000L

/**
 * Safety ceiling on how long a burst may stay on screen. The clip's own
 * `onFinished` ends it at its real length, so this only covers a player
 * that stalls and never reports back. It is deliberately far above any
 * real clip: a shorter ceiling silently cut an operator's scene off
 * partway through, which read as a broken, half-length animation.
 */
private const val MAX_GIFT_HOLD_MS = 120_000L

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
    clientBuilder: IRoleBasedHttpClientBuilder? = null,
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

    // Completed by the player once the clip is actually on screen, so
    // the burst's hold clock starts then rather than at enqueue time.
    var svgaStarted by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }

    // Per-gift sound effect, decoded once up front so the very first
    // gift is not silent.
    val sounds = rememberGiftSounds(clientBuilder)
    LaunchedEffect(Unit) { RoomGiftCatalog.effective.forEach { sounds.preload(it.sound) } }

    // Pull the operator-editable catalog once per process. The store
    // applies the on-disk copy first and then refreshes over the
    // network, so a cold start is never blocked on a request and a
    // failed fetch leaves the last good catalog in place.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val builder = clientBuilder ?: return@LaunchedEffect
        RemoteGiftStore.catalogUrl = BuildConfig.GIFT_CATALOG_URL
        RemoteGiftStore.load(context, builder)
    }

    // Warm every remote clip as soon as the catalog is known. A
    // dashboard clip is multi-megabyte; without this the first send
    // downloaded it while the burst's hold window ran out, so the
    // scene never appeared. Prefetching once means a send is instant.
    // Keyed on *both* asset kinds: a gift may be video-only, and a
    // re-published clip (same id, new URL) must warm the new file.
    LaunchedEffect(RoomGiftCatalog.effective.map { it.video to it.svga }) {
        val builder = clientBuilder ?: return@LaunchedEffect
        RoomGiftCatalog.effective.forEach { sku ->
            // Warm the alpha video when the gift has one — it is the clip
            // that will actually play, and it is small enough that this
            // lands well before the first send.
            sku.video?.let { v ->
                val remote = giftAssetOf(v) as? GiftAsset.Remote ?: return@let
                GiftAssetDownloader.localFileOrNull(context, remote.url, builder, ".mp4")
                return@forEach
            }
            val remote = sku.svga?.let { giftAssetOf(it) as? GiftAsset.Remote } ?: return@forEach
            val file = GiftAssetDownloader.localFileOrNull(context, remote.url, builder, ".svga") ?: return@forEach
            // Decode the clip too: the parser caches only the file, so a
            // send would otherwise re-parse a multi-megabyte asset while
            // the burst is already on screen.
            warmSvgaEntity(context, file)
        }
    }

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
            val sku = RoomGiftCatalog.byId(gift.skuId)
            current = gift
            sounds.play(sku?.sound)
            // A gift has a real cinematic if it carries either an alpha
            // video or an SVGA clip; both signal ready/finished through
            // the same deferreds, so the hold clock is identical.
            if (sku?.video != null || sku?.svga != null) {
                val done = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                svgaDone = done
                svgaStarted = started
                // Hold the burst for the clip's real runtime, with a
                // floor so the shortest (3 s `gen_*`) clips still get a
                // full-screen beat instead of a blink. The clip's own
                // `onFinished` ends the burst at its true length; the
                // ceiling below is only a guard against a stalled player.
                val holdMs = maxOf(giftBurstMillis(gift.tier), MIN_GIFT_HOLD_MS)
                // Wait for the clip to be on screen before starting the
                // clock: on a cold cache the download can outlast the
                // hold window, and timing from enqueue would show the
                // message with no scene at all. The ceiling covers a
                // clip that never becomes ready (no network).
                withTimeoutOrNull(READY_TIMEOUT_MS) { started.await() }
                val startedAt = System.currentTimeMillis()
                withTimeoutOrNull(MAX_GIFT_HOLD_MS) { done.await() }
                svgaDone = null
                svgaStarted = null
                // If the clip finished before the floor (a short 3 s
                // asset), keep it on screen for the remainder so the gift
                // reads as a moment, not an emoji flash. SVGAImageView
                // holds its final frame, so we only wait out the rest.
                val remaining = holdMs - (System.currentTimeMillis() - startedAt)
                if (remaining > 0) delay(remaining)
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
                onSvgaReady = { svgaStarted?.complete(Unit) },
                modifier = modifier,
                clientBuilder = clientBuilder,
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
    onSvgaReady: () -> Unit,
    modifier: Modifier = Modifier,
    clientBuilder: IRoleBasedHttpClientBuilder? = null,
) {
    val sku = RoomGiftCatalog.byId(gift.skuId)
    // Prefer the alpha-packed video: it is a fraction of the SVGA's size
    // and decodes in hardware, so the burst starts sooner and costs less
    // RAM. SVGA stays as the fallback for gifts authored before the
    // packer existed and for any clip the operator has not re-published.
    val videoAsset = sku?.video
    val videoWidth = sku?.videoWidth ?: 0
    val videoHeight = sku?.videoHeight ?: 0
    val svgaAsset = sku?.svga
    val videoClip = videoAsset?.takeIf { videoWidth > 0 && videoHeight > 0 }
    val theme = giftThemeFor(gift.skuId)
    val epic = gift.tier >= 3
    // The operator can size a takeover from the dashboard; the bundled
    // catalog leaves this at 1.0 (clips are authored full-frame).
    val clipScale = (sku?.scale ?: 1f).coerceIn(0.4f, 1.2f)
    // The operator can also nudge the takeover off centre, as a fraction
    // of the screen, so a clip need not be dead-centre over the room.
    val clipOffsetX = (sku?.offsetX ?: 0f).coerceIn(-0.5f, 0.5f)
    val clipOffsetY = (sku?.offsetY ?: 0f).coerceIn(-0.5f, 0.5f)
    // Match the queue's hold so the marquee/scrim animation runs the
    // whole time the burst is on screen (a short authored clip still
    // gets the full-screen beat the queue holds it for).
    val durationMs = maxOf(giftBurstMillis(gift.tier), MIN_GIFT_HOLD_MS).toInt()

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

    // The clip is scaled/translated in a graphicsLayer, so it needs the
    // real pixel size to turn the fractional offsets into translations.
    var boxW by remember { mutableStateOf(0) }
    var boxH by remember { mutableStateOf(0) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged {
                    boxW = it.width
                    boxH = it.height
                },
        contentAlignment = Alignment.Center,
    ) {
        if (videoClip != null) {
            // The clip IS the gift's cinematic. It is authored full-frame
            // and transparent, so it is drawn at full size with nothing
            // behind it; the operator may still size it down and move it
            // from the dashboard (clipScale / clipOffset*) to place it
            // over the room.
            AlphaVideoGiftPlayer(
                asset = videoClip,
                width = videoWidth,
                height = videoHeight,
                onFinished = onSvgaFinished,
                onReady = onSvgaReady,
                clientBuilder = clientBuilder,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = clipScale
                            scaleY = clipScale
                            translationX = clipOffsetX * boxW
                            translationY = clipOffsetY * boxH
                        },
            )
            GiftBurstCaption(
                epic = epic,
                senderName = senderName,
                receiverName = receiverName,
                gift = gift,
                theme = theme,
                progress = p,
            )
        } else if (svgaAsset != null) {
            // The clip IS the gift's cinematic. It is authored
            // full-frame and transparent, so it is drawn at full size
            // with nothing behind it; the operator may still size it
            // down and move it from the dashboard (clipScale /
            // clipOffset*) to place it over the room.
            SvgaGiftPlayer(
                asset = svgaAsset,
                onFinished = onSvgaFinished,
                onReady = onSvgaReady,
                clientBuilder = clientBuilder,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = clipScale
                            scaleY = clipScale
                            translationX = clipOffsetX * boxW
                            translationY = clipOffsetY * boxH
                        },
            )
            GiftBurstCaption(
                epic = epic,
                senderName = senderName,
                receiverName = receiverName,
                gift = gift,
                theme = theme,
                progress = p,
            )
        } else {
            // No clip: fall back to the procedural emblem + its own
            // themed backdrop (the only case where a backdrop is drawn).
            if (epic) {
                EpicBackdrop(gift = gift, theme = theme, progress = p)
            }
            Emblem(gift = gift, theme = theme, senderName = senderName, receiverName = receiverName, epic = epic, progress = p)
        }
    }
}

/**
 * The sender/receiver caption drawn over a gift's clip: a full marquee for
 * a tier-3+ takeover, a compact pill otherwise. Shared by the video and the
 * SVGA branch so both paths present the gift identically.
 */
@Composable
private fun BoxScope.GiftBurstCaption(
    epic: Boolean,
    senderName: String,
    receiverName: String?,
    gift: RoomGift,
    theme: RoomGiftTheme,
    progress: Float,
) {
    if (epic) {
        GiftMarquee(
            senderName = senderName,
            receiverName = receiverName,
            gift = gift,
            theme = theme,
            progress = progress,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
    } else {
        GiftSenderChip(
            senderName = senderName,
            gift = gift,
            theme = theme,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
        )
    }
}

/** Small "emoji + sender" pill under a standard/premium gift's clip. */
@Composable
private fun GiftSenderChip(
    senderName: String,
    gift: RoomGift,
    theme: RoomGiftTheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(Color(theme.accentDeep).copy(alpha = 0.72f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = "${gift.emoji}  $senderName",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Dark scrim + themed rays + the gift's own particle motif + (legendary) flash & shock rings. */
@Composable
private fun EpicBackdrop(
    gift: RoomGift,
    theme: RoomGiftTheme,
    progress: Float,
) {
    val legendary = gift.tier >= 4
    val accent = Color(theme.accent)
    val accentDeep = Color(theme.accentDeep)
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

        // Scrim, tinted by the gift's own deep accent so the room behind
        // reads warm/cool/violet depending on what was sent.
        if (scrimAlpha > 0f) {
            drawRect(
                brush =
                    Brush.radialGradient(
                        colors = listOf(accentDeep.copy(alpha = scrimAlpha), Color.Black.copy(alpha = scrimAlpha)),
                        center = Offset(cx, cy),
                        radius = maxR * 0.9f,
                    ),
            )
        }

        // Rotating light rays, spin direction and colour taken from the theme.
        val rayAlpha = (scrimAlpha / 0.72f) * 0.5f
        if (rayAlpha > 0.01f) {
            val rotation = progress * theme.rayRotation
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
                drawPath(path = path, color = accent.copy(alpha = rayAlpha * 0.35f))
            }
        }

        // Shock rings expanding from the centre (legendary only).
        if (legendary) {
            for (i in 0 until 3) {
                val rp = ((progress - i * 0.12f) / 0.5f).coerceIn(0f, 1f)
                if (rp <= 0f) continue
                drawCircle(
                    color = accent.copy(alpha = (1f - rp) * 0.6f),
                    radius = maxR * 0.5f * rp,
                    center = Offset(cx, cy),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // The gift's own particle motif — this is what makes each gift
        // look different instead of every gift sharing one confetti layer.
        val particleAlpha = (scrimAlpha / 0.72f) * 0.9f
        if (particleAlpha > 0.01f) {
            drawMotif(
                motif = theme.motif,
                variant = theme.variant,
                seed = theme.seed,
                progress = progress,
                alpha = particleAlpha,
                accent = accent,
                accentDeep = accentDeep,
                cx = cx,
                cy = cy,
                maxR = maxR,
            )
        }

        // White flash (legendary).
        if (flashAlpha > 0.01f) {
            drawRect(color = Color.White.copy(alpha = flashAlpha))
        }
    }
}

/**
 * Draws the per-gift particle layer. Every branch is driven from the
 * same [progress] clock and uses a deterministic per-particle seed, so
 * a burst never re-randomises between frames. [variant] scales the
 * particle count so two gifts sharing a motif still differ. Kept as
 * one dispatch so adding a motif is a single `when` branch plus its
 * enum entry.
 */
private fun DrawScope.drawMotif(
    motif: GiftMotif,
    variant: Int,
    seed: Int,
    progress: Float,
    alpha: Float,
    accent: Color,
    accentDeep: Color,
    cx: Float,
    cy: Float,
    maxR: Float,
) {
    when (motif) {
        GiftMotif.Confetti -> drawConfetti(progress, alpha, accent, accentDeep, variant, seed)
        GiftMotif.Petals -> drawPetals(progress, alpha, accent, variant, seed)
        GiftMotif.Sparks -> drawSparks(progress, alpha, accent, cx, cy, maxR, variant, seed)
        GiftMotif.Stars -> drawStars(progress, alpha, accent, variant, seed)
        GiftMotif.Bubbles -> drawBubbles(progress, alpha, accent, variant, seed)
        GiftMotif.Rings -> drawRings(progress, alpha, accent, cx, cy, maxR, variant, seed)
        GiftMotif.Shockwave -> drawShockwave(progress, alpha, accent, cx, cy, maxR, seed)
        GiftMotif.Hearts -> drawHearts(progress, alpha, accent, variant, seed)
        GiftMotif.Feathers -> drawFeathers(progress, alpha, accent, variant, seed)
        GiftMotif.Fireworks -> drawFireworks(progress, alpha, accent, cx, cy, maxR, variant, seed)
        GiftMotif.Snow -> drawSnow(progress, alpha, accent, variant, seed)
        GiftMotif.Leaves -> drawLeaves(progress, alpha, accent, variant, seed)
    }
}

/** Particle count for a base count and a theme variant (0..2). */
private fun density(
    base: Int,
    variant: Int,
): Int = base + variant * (base / 3)

/** Deterministic 0..1 from an index, salt and the gift's layout seed. */
private fun frac(
    i: Int,
    salt: Int,
    seed: Int,
): Float {
    val h = (i + 1) * 2654435761L + salt * 40503L + seed * 2246822519L
    return (((h ushr 8) and 0xFFFF).toFloat() / 0xFFFF.toFloat())
}

private fun DrawScope.drawConfetti(
    progress: Float,
    alpha: Float,
    accent: Color,
    accentDeep: Color,
    variant: Int,
    seed: Int,
) {
    val colors = listOf(accent, accentDeep, Color(0xFFFF8A65), Color(0xFF80DEEA), Color(0xFFF48FB1))
    val strips = density(46, variant)
    for (i in 0 until strips) {
        val x = frac(i, 1, seed)
        val delay = frac(i, 2, seed) * 0.4f
        val fall = ((progress - delay) / 0.7f).coerceIn(0f, 1f)
        if (fall <= 0f) continue
        val drift = sin(fall * 6f + i) * 0.04f
        val h = 14f + frac(i, 3, seed) * 12f
        drawRect(
            color = colors[i % colors.size].copy(alpha = alpha * (1f - fall * 0.4f)),
            topLeft = Offset((x + drift) * size.width, fall * size.height),
            size = Size(6f, h),
        )
    }
}

/** Flower petals drifting down with a gentle sway. */
private fun DrawScope.drawPetals(
    progress: Float,
    alpha: Float,
    accent: Color,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(34, variant)) {
        val x = frac(i, 11, seed)
        val delay = frac(i, 12, seed) * 0.5f
        val fall = ((progress - delay) / 0.8f).coerceIn(0f, 1f)
        if (fall <= 0f) continue
        val sway = sin(fall * 5f + i * 0.7f) * 0.06f
        val px = (x + sway) * size.width
        val py = fall * size.height
        val r = 7f + frac(i, 13, seed) * 8f
        drawOval(
            color = accent.copy(alpha = alpha * (1f - fall * 0.35f)),
            topLeft = Offset(px, py),
            size = Size(r * 1.5f, r),
        )
    }
}

/** Sharp sparks shooting outward from the centre, then fading. */
private fun DrawScope.drawSparks(
    progress: Float,
    alpha: Float,
    accent: Color,
    cx: Float,
    cy: Float,
    maxR: Float,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(60, variant)) {
        val ang = (frac(i, 21, seed) * 360f) * (PI.toFloat() / 180f)
        val speed = 0.5f + frac(i, 22, seed) * 0.9f
        val dist = progress * speed * maxR * 0.75f
        if (dist <= 0f) continue
        val fade = (1f - progress).coerceIn(0f, 1f)
        val len = 6f + frac(i, 23, seed) * 16f
        drawLine(
            color = accent.copy(alpha = alpha * fade),
            start = Offset(cx + cos(ang) * dist, cy + sin(ang) * dist),
            end = Offset(cx + cos(ang) * (dist + len), cy + sin(ang) * (dist + len)),
            strokeWidth = 2f,
        )
    }
}

/** Twinkling stars that pop in at staggered times and hold. */
private fun DrawScope.drawStars(
    progress: Float,
    alpha: Float,
    accent: Color,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(40, variant)) {
        val x = frac(i, 31, seed)
        val y = frac(i, 32, seed)
        val delay = frac(i, 33, seed) * 0.6f
        val pop = ((progress - delay) / 0.15f).coerceIn(0f, 1f)
        if (pop <= 0f) continue
        val twinkle = 0.6f + 0.4f * sin(progress * 12f + i)
        val r = (3f + frac(i, 34, seed) * 6f) * pop
        drawCircle(
            color = accent.copy(alpha = alpha * twinkle),
            radius = r,
            center = Offset(x * size.width, y * size.height),
        )
    }
}

/** Soft bubbles rising and fading, like a drink or a spa. */
private fun DrawScope.drawBubbles(
    progress: Float,
    alpha: Float,
    accent: Color,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(26, variant)) {
        val x = frac(i, 41, seed)
        val delay = frac(i, 42, seed) * 0.5f
        val rise = ((progress - delay) / 0.85f).coerceIn(0f, 1f)
        if (rise <= 0f) continue
        val r = 6f + frac(i, 43, seed) * 16f
        val py = (1f - rise) * size.height
        drawCircle(
            color = accent.copy(alpha = alpha * (1f - rise) * 0.7f),
            radius = r,
            center = Offset(x * size.width, py),
            style = Stroke(width = 2f),
        )
    }
}

/** Concentric rings pulsing out from the centre — planets, orbits. */
private fun DrawScope.drawRings(
    progress: Float,
    alpha: Float,
    accent: Color,
    cx: Float,
    cy: Float,
    maxR: Float,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(5, variant)) {
        val rp = ((progress - i * 0.09f) / 0.6f).coerceIn(0f, 1f)
        if (rp <= 0f) continue
        // Seed jitters each ring's radius so two Rings gifts aren't the
        // same concentric pattern in a different colour.
        val jitter = 0.85f + frac(i, 101, seed) * 0.3f
        drawCircle(
            color = accent.copy(alpha = alpha * (1f - rp) * 0.7f),
            radius = maxR * 0.42f * rp * jitter,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5f),
        )
    }
}

/** One hard shockwave ring plus a soft echo — rings, diamonds. */
private fun DrawScope.drawShockwave(
    progress: Float,
    alpha: Float,
    accent: Color,
    cx: Float,
    cy: Float,
    maxR: Float,
    seed: Int,
) {
    val rp = (progress / 0.5f).coerceIn(0f, 1f)
    if (rp > 0f) {
        // Seed varies the ring width so two Shockwave gifts (ring vs
        // diamond) don't render an identical single hoop.
        val width = 3f + frac(0, 102, seed) * 4f
        drawCircle(
            color = accent.copy(alpha = alpha * (1f - rp)),
            radius = maxR * 0.55f * rp,
            center = Offset(cx, cy),
            style = Stroke(width = width),
        )
    }
    val echo = ((progress - 0.18f) / 0.55f).coerceIn(0f, 1f)
    if (echo > 0f) {
        drawCircle(
            color = accent.copy(alpha = alpha * (1f - echo) * 0.5f),
            radius = maxR * 0.4f * echo,
            center = Offset(cx, cy),
            style = Stroke(width = 2f),
        )
    }
}

/** Little hearts floating upward. */
private fun DrawScope.drawHearts(
    progress: Float,
    alpha: Float,
    accent: Color,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(30, variant)) {
        val x = frac(i, 51, seed)
        val delay = frac(i, 52, seed) * 0.5f
        val rise = ((progress - delay) / 0.85f).coerceIn(0f, 1f)
        if (rise <= 0f) continue
        val sway = sin(rise * 4f + i) * 0.05f
        val s = 8f + frac(i, 53, seed) * 10f
        val px = (x + sway) * size.width
        val py = (1f - rise) * size.height
        // Two lobes + a V = a heart, drawn as paths so no glyph font is needed.
        val path =
            Path().apply {
                moveTo(px, py + s * 0.8f)
                cubicTo(px - s, py + s * 0.1f, px - s * 0.4f, py - s * 0.6f, px, py - s * 0.1f)
                cubicTo(px + s * 0.4f, py - s * 0.6f, px + s, py + s * 0.1f, px, py + s * 0.8f)
                close()
            }
        drawPath(path, accent.copy(alpha = alpha * (1f - rise * 0.4f)))
    }
}

/** Feathers tumbling down slowly — angels, flight. */
private fun DrawScope.drawFeathers(
    progress: Float,
    alpha: Float,
    accent: Color,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(20, variant)) {
        val x = frac(i, 61, seed)
        val delay = frac(i, 62, seed) * 0.55f
        val fall = ((progress - delay) / 0.85f).coerceIn(0f, 1f)
        if (fall <= 0f) continue
        val sway = sin(fall * 4f + i) * 0.09f
        val w = 6f + frac(i, 63, seed) * 8f
        drawOval(
            color = accent.copy(alpha = alpha * (1f - fall * 0.3f) * 0.85f),
            topLeft = Offset((x + sway) * size.width, fall * size.height),
            size = Size(w, w * 2.2f),
        )
    }
}

/** Bursts of radial embers exploding from a few centres. */
private fun DrawScope.drawFireworks(
    progress: Float,
    alpha: Float,
    accent: Color,
    cx: Float,
    cy: Float,
    maxR: Float,
    variant: Int,
    seed: Int,
) {
    val bursts = density(4, variant)
    for (b in 0 until bursts) {
        val bx = (0.3f + frac(b, 71, seed) * 0.4f) * size.width
        val by = (0.25f + frac(b, 72, seed) * 0.4f) * size.height
        val t0 = frac(b, 73, seed) * 0.4f
        val t = ((progress - t0) / 0.5f).coerceIn(0f, 1f)
        if (t <= 0f) continue
        val n = 22
        for (i in 0 until n) {
            val ang = (i * (360f / n) + frac(b * 31 + i, 74, seed) * 20f) * (PI.toFloat() / 180f)
            val d = t * maxR * 0.22f * (0.7f + frac(i, 75, seed) * 0.6f)
            drawCircle(
                color = accent.copy(alpha = alpha * (1f - t)),
                radius = 2.5f,
                center = Offset(bx + cos(ang) * d, by + sin(ang) * d),
            )
        }
    }
    // A faint central glow so the bursts read as fireworks, not dots.
    drawCircle(
        brush = Brush.radialGradient(listOf(accent.copy(alpha = alpha * 0.35f), Color.Transparent), center = Offset(cx, cy), radius = maxR * 0.5f),
        radius = maxR * 0.5f,
        center = Offset(cx, cy),
    )
}

/** Fine snow drifting down — winter, ice cream. */
private fun DrawScope.drawSnow(
    progress: Float,
    alpha: Float,
    accent: Color,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(70, variant)) {
        val x = frac(i, 81, seed)
        val delay = frac(i, 82, seed) * 0.4f
        val fall = ((progress - delay) / 0.9f).coerceIn(0f, 1f)
        if (fall <= 0f) continue
        val sway = sin(fall * 6f + i) * 0.03f
        val r = 1.5f + frac(i, 83, seed) * 3f
        drawCircle(
            color = accent.copy(alpha = alpha * (1f - fall * 0.3f) * 0.9f),
            radius = r,
            center = Offset((x + sway) * size.width, fall * size.height),
        )
    }
}

/** Green leaves swirling down — nature, music. */
private fun DrawScope.drawLeaves(
    progress: Float,
    alpha: Float,
    accent: Color,
    variant: Int,
    seed: Int,
) {
    for (i in 0 until density(24, variant)) {
        val x = frac(i, 91, seed)
        val delay = frac(i, 92, seed) * 0.5f
        val fall = ((progress - delay) / 0.85f).coerceIn(0f, 1f)
        if (fall <= 0f) continue
        val spin = fall * 6f + i
        val sway = sin(spin) * 0.07f
        val s = 6f + frac(i, 93, seed) * 7f
        val px = (x + sway) * size.width
        val py = fall * size.height
        val path =
            Path().apply {
                moveTo(px, py)
                quadraticTo(px + s * cos(spin), py + s * sin(spin), px + s * 0.6f, py + s * 1.4f)
                quadraticTo(px - s * 0.2f, py + s * 0.9f, px, py)
                close()
            }
        drawPath(path, accent.copy(alpha = alpha * (1f - fall * 0.3f)))
    }
}

/** The floating gift emblem + quantity + names. */
@Composable
private fun Emblem(
    gift: RoomGift,
    theme: RoomGiftTheme,
    senderName: String,
    receiverName: String?,
    epic: Boolean,
    progress: Float,
) {
    val accent = Color(theme.accent)
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
                                .background(accent)
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
                    theme = theme,
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
    theme: RoomGiftTheme,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val accent = Color(theme.accent)
    val accentDeep = Color(theme.accentDeep)
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
                .background(accentDeep.copy(alpha = 0.9f))
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
                        .background(accent)
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
