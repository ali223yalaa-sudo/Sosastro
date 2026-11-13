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

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.opensource.svgaplayer.SVGACallback
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity

/**
 * Plays a bundled SVGA gift animation full-screen. Thin Compose
 * bridge over the official [SVGAImageView] (Apache-2.0): decode the
 * asset once, start it, and report completion so the overlay's queue
 * can advance to the next gift.
 *
 * The player is intentionally isolated to this one composable — the
 * rest of the overlay (queue, marquee, fallback emoji treatment)
 * stays platform-agnostic Compose.
 */
@Composable
fun SvgaGiftPlayer(
    assetPath: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the latest callback without re-creating the view.
    val finished = remember { Ref(onFinished) }
    finished.value = onFinished

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SVGAImageView(ctx).apply {
                loops = 1
                isClickable = false
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
                SVGAParser(ctx).decodeFromAssets(
                    assetPath,
                    object : SVGAParser.ParseCompletion {
                        override fun onComplete(videoItem: SVGAVideoEntity) {
                            setVideoItem(videoItem)
                            startAnimation()
                        }

                        override fun onError() {
                            Log.w("SvgaGiftPlayer", "failed to decode $assetPath")
                            finished.value()
                        }
                    },
                )
            }
        },
    )
}

/** Minimal mutable holder so a lambda can be updated without recomposing the view. */
private class Ref<T>(
    var value: T,
)
