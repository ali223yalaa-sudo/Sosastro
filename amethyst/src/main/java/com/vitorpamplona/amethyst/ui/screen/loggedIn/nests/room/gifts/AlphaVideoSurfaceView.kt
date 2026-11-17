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
 * It must be composited *with* its own per-pixel alpha: the gift's transparent
 * regions have to reveal the room behind it, not a black rectangle. On Android
 * a [GLSurfaceView] only blends with the window's content when its surface is
 * placed on top of the window and marked translucent — `setZOrderOnTop(false)`
 * (the default) parks the surface *behind* the window, where the transparent
 * pixels composite against nothing and the gift shows as a black box. So the
 * surface is translucent and z-ordered on top; its clear colour is fully
 * transparent, so the room shows through everywhere the clip is see-through.
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
        // Must be set before the surface is created (before setRenderer):
        // a z-order-on-top surface is the only way a SurfaceView blends its
        // alpha with the window content instead of replacing it with black.
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        renderer.onSurfaceReady = { surface -> onSurfaceReady?.invoke(surface) }
    }
}
