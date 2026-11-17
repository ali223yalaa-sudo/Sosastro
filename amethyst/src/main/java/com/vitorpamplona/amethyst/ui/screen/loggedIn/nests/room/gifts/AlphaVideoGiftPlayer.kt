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

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vitorpamplona.amethyst.model.privacyOptions.IRoleBasedHttpClientBuilder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays one gift's AlphaPlayer-packed clip full-screen and reports completion.
 *
 * The clip is a small H.264 video whose alpha rides in a second frame below
 * the colour one; [AlphaVideoRenderer] recombines them on the GPU. Decoding
 * uses the platform's hardware video block, so a gift costs a fraction of the
 * CPU and RAM an SVGA clip does and starts far sooner — the point of the
 * whole exercise.
 *
 * A remote clip is downloaded once into the app cache (see
 * [GiftAssetDownloader]) and decoded from that local file, so a replay costs
 * no network.
 */
@Composable
fun AlphaVideoGiftPlayer(
    asset: String,
    width: Int,
    height: Int,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    clientBuilder: IRoleBasedHttpClientBuilder? = null,
    onReady: () -> Unit = {},
    onError: () -> Unit = {},
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
) {
    val context = LocalContext.current
    val source = giftAssetOf(asset)

    // A remote clip must be on disk before the decoder can open it.
    val resolved by
        produceState<File?>(null, asset) {
            value =
                when (source) {
                    is GiftAsset.Bundled -> File(context.cacheDir, source.path).takeIf { it.exists() }
                    is GiftAsset.Remote ->
                        clientBuilder?.let { GiftAssetDownloader.localFileOrNull(context, source.url, it, ".mp4") }
                    null -> null
                }
            // A clip that could not be fetched has nothing to play. Signal
            // the caller so it can fall back to the SVGA copy (or end the
            // burst) instead of the queue stalling on a burst that will
            // never start.
            if (value == null) onError()
        }

    val file = resolved ?: return

    val renderer = remember(width, height) { AlphaVideoRenderer(width, height) }
    val player = remember { ExoPlayer.Builder(context).build() }

    val finished = remember { Ref(onFinished) }
    finished.value = onFinished
    val ready = remember { Ref(onReady) }
    ready.value = onReady
    val errored = remember { Ref(onError) }
    errored.value = onError

    // Each signal must fire at most once per burst: a repeated `ready` would
    // restart the hold clock and a repeated `finished` would advance the
    // queue twice, dropping the next gift.
    val signalledReady = remember { AtomicBoolean(false) }
    val signalledEnd = remember { AtomicBoolean(false) }

    DisposableEffect(file) {
        val listener =
            object : Player.Listener {
                // The hold clock must start when the scene is actually on
                // screen, not when playback was requested — otherwise a slow
                // first frame eats into the burst and the gift flashes late.
                override fun onRenderedFirstFrame() {
                    if (signalledReady.compareAndSet(false, true)) ready.value()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        if (signalledEnd.compareAndSet(false, true)) finished.value()
                    }
                }

                // A decode/IO failure must never strand the queue. Fall back
                // to the SVGA copy when there is one; otherwise end the burst
                // so the next gift still plays.
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "gift video failed: $asset", error)
                    if (signalledEnd.compareAndSet(false, true)) errored.value()
                }
            }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.repeatMode = Player.REPEAT_MODE_OFF
        // The clip carries no audio track (the gift's sound is played
        // separately by [GiftSounds]); mute anyway so a stray track can
        // never double up or leak past the burst.
        player.volume = 0f
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            AlphaVideoSurfaceView(ctx, renderer).apply {
                // The GL thread owns the decoder's input surface, so the
                // player can only be attached once it exists. The media item
                // is already prepared by now, so attaching the surface and
                // flipping `playWhenReady` starts the first frame immediately
                // — no race where the burst begins with nothing on screen.
                onSurfaceReady = { surface ->
                    post {
                        player.setVideoSurface(surface)
                        player.playWhenReady = true
                    }
                }
            }
        },
        update = { view -> view.setPlacement(scale, offsetX, offsetY) },
    )

    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }
}

private const val TAG = "AlphaVideoGiftPlayer"
