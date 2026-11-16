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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vitorpamplona.amethyst.model.privacyOptions.IRoleBasedHttpClientBuilder
import java.io.File

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
            // A clip that could not be fetched has nothing to play. Report
            // ready so the overlay's queue is not stalled waiting for a
            // burst that will never start.
            if (value == null) onReady()
        }

    val file = resolved ?: return

    val renderer = remember(width, height) { AlphaVideoRenderer(width, height) }
    val player = remember { ExoPlayer.Builder(context).build() }

    val finished = remember { Ref(onFinished) }
    finished.value = onFinished

    DisposableEffect(file) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) finished.value()
                }
            },
        )
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            AlphaVideoSurfaceView(ctx, renderer).apply {
                // The GL thread owns the decoder's input surface, so the
                // player can only be attached once it exists.
                onSurfaceReady = { surface ->
                    post {
                        player.setVideoSurface(surface)
                        onReady()
                    }
                }
            }
        },
    )

    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }
}
