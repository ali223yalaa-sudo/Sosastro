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
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.Surface

/**
 * A [GLSurfaceView] that composites an AlphaPlayer-packed gift clip through
 * [AlphaVideoRenderer] and exposes the decoder's input surface once the GL
 * thread has created it.
 *
 * It is deliberately transparent ([PixelFormat.TRANSLUCENT] plus
 * `setZOrderOnTop(false)`): the gift must sit over the room, so the view's
 * own pixels have to blend with what is behind it rather than replace it.
 */
internal class AlphaVideoSurfaceView(
    context: Context,
    private val renderer: AlphaVideoRenderer,
) : GLSurfaceView(context) {
    /** Invoked on the GL thread with the surface the video decoder renders into. */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(false)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        renderer.onSurfaceReady = { surface -> onSurfaceReady?.invoke(surface) }
    }
}
