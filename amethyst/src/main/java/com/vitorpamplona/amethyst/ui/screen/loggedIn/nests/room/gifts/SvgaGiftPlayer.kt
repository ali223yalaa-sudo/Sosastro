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

import android.content.Context
import android.util.Log
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.opensource.svgaplayer.SVGACallback
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import com.vitorpamplona.amethyst.model.privacyOptions.IRoleBasedHttpClientBuilder

/**
 * Plays one gift's SVGA clip full-screen and reports completion.
 *
 * Handles both a bundled asset and a remote URL: a remote clip is
 * downloaded once into the app cache (see [GiftAssetDownloader]) and
 * then decoded from that local file, so replaying a gift costs no
 * network. While a download is in flight the player renders nothing
 * rather than a blank frame.
 */
@Composable
fun SvgaGiftPlayer(
    asset: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    clientBuilder: IRoleBasedHttpClientBuilder? = null,
) {
    // Keep the latest callback without re-creating the view.
    val finished = remember { Ref(onFinished) }
    finished.value = onFinished

    val context = LocalContext.current
    val source = giftAssetOf(asset)
    // Resolve a remote clip to a local file before touching the player;
    // a bundled path resolves synchronously to itself.
    val resolved by
        produceState<GiftSource?>(null, asset) {
            value =
                when (source) {
                    is GiftAsset.Bundled -> GiftSource.Bundled(source.path)
                    is GiftAsset.Remote ->
                        clientBuilder
                            ?.let { GiftAssetDownloader.localFileOrNull(context, source.url, it, ".svga") }
                            ?.let { GiftSource.Local(it) }
                    null -> null
                }
        }

    val ready = resolved ?: return

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            SVGAImageView(ctx).apply {
                loops = 1
                isClickable = false
                scaleType = ImageView.ScaleType.FIT_CENTER
                // Hold the last frame until the overlay swaps the gift
                // out, so a short clip does not blink to an empty box.
                clearsAfterStop = false
                callback =
                    object : SVGACallback {
                        override fun onFinished() {
                            finished.value()
                        }

                        override fun onPause() = Unit

                        override fun onRepeat() = Unit

                        override fun onStep(
                            frame: Int,
                            percentage: Double,
                        ) = Unit
                    }
            }
        },
        update = { view ->
            if (view.tag != asset) {
                view.tag = asset
                decodeInto(view, view.context, ready, finished)
            }
        },
    )
}

private fun decodeInto(
    view: SVGAImageView,
    context: Context,
    source: GiftSource,
    finished: Ref<() -> Unit>,
) {
    val parser = SVGAParser(context)
    val onComplete =
        object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) {
                view.setVideoItem(videoItem)
                view.startAnimation()
            }

            override fun onError() {
                Log.w("SvgaGiftPlayer", "failed to decode $source")
                finished.value()
            }
        }
    when (source) {
        is GiftSource.Bundled -> parser.decodeFromAssets(source.path, onComplete)
        is GiftSource.Local -> parser.decodeFromInputStream(source.file.inputStream(), source.file.name, onComplete, true)
    }
}

private sealed interface GiftSource {
    data class Bundled(
        val path: String,
    ) : GiftSource

    data class Local(
        val file: java.io.File,
    ) : GiftSource
}

private class Ref<T>(
    var value: T,
)
