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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.privateDM.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.mikepenz.hypnoticcanvas.shaderBackground
import com.mikepenz.hypnoticcanvas.shaders.MeshGradient
import com.mikepenz.hypnoticcanvas.shaders.Shader

/**
 * Messenger-style animated chat wallpaper.
 *
 * Messenger's animated chat themes are not video or GIF files — they are drawn
 * procedurally on the GPU every frame, which is why they cost nothing to ship and
 * stay light on the device. We reproduce the same pattern with HypnoticCanvas:
 * a [MeshGradient] shader built from the active theme's own colours, so every
 * palette keeps its identity while the background slowly drifts.
 *
 * Shaders run on Android 13+ (API 33). Below that the library draws the
 * [fallback][fallbackBrushFor] brush instead, which is a static gradient derived
 * from the same colours, so older devices still get a themed background.
 */
private const val BACKGROUND_SPEED = 0.35f
private const val BACKGROUND_SCALE = 1.1f

/**
 * A scrim laid over the shader so message bubbles and text stay legible while the
 * colours move. The theme's own background colour is reused so the scrim never
 * shifts the palette's hue.
 */
private const val BACKGROUND_SCRIM_ALPHA = 0.62f

/**
 * Solid colour set for the animated gradient. The palette's bubble colours carry
 * alpha in light mode (they are translucent chips), so they are composited over the
 * theme background before being handed to the shader — [MeshGradient] only reads RGB.
 */
fun animatedBackgroundColors(palette: MessengerChatPalette): Array<Color> {
    val base = palette.background
    return arrayOf(
        palette.accent,
        palette.me.compositeOver(base),
        palette.them.compositeOver(base),
        base,
    )
}

/** The shader that animates [palette]'s colours. */
fun animatedBackgroundShader(palette: MessengerChatPalette): Shader =
    MeshGradient(
        colors = animatedBackgroundColors(palette),
        speed = BACKGROUND_SPEED,
        scale = BACKGROUND_SCALE,
    )

/**
 * Static gradient shown on devices without shader support (below API 33) and used
 * as the theme-picker preview, where animating 15 shaders at once would be wasteful.
 */
fun fallbackBrushFor(palette: MessengerChatPalette): Brush {
    val colors = animatedBackgroundColors(palette)
    return Brush.linearGradient(colors.toList())
}

/**
 * Paints the animated wallpaper behind the conversation. Drop this as the first
 * child of the chatroom's root [Box]; the feed composes on top of it.
 */
@Composable
fun AnimatedChatBackground(
    palette: MessengerChatPalette,
    modifier: Modifier = Modifier,
) {
    val shader = remember(palette) { animatedBackgroundShader(palette) }
    val fallback = remember(palette) { fallbackBrushFor(palette) }

    Box(
        modifier
            .fillMaxSize()
            // Painted first so the scrim (and the shader itself, before its first
            // frame) always sit on an opaque themed base.
            .background(palette.background)
            .shaderBackground(shader = shader, speed = 1f, fallback = { fallback }),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.background.copy(alpha = BACKGROUND_SCRIM_ALPHA)),
        )
    }
}
