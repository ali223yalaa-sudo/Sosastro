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
package com.vitorpamplona.amethyst.commons.robohash

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData

/**
 * Single shared Instagram-style blank profile picture used for every user
 * without a custom avatar (grey circle + simple person silhouette).
 */
object DefaultUserAvatar {
    private var light: ImageVector? = null
    private var dark: ImageVector? = null

    fun get(isLightTheme: Boolean): ImageVector {
        if (isLightTheme) {
            light?.let { return it }
            return build(isLightTheme = true).also { light = it }
        }
        dark?.let { return it }
        return build(isLightTheme = false).also { dark = it }
    }

    private fun build(isLightTheme: Boolean): ImageVector {
        // Instagram blank avatar: soft grey field + lighter person silhouette.
        val background =
            SolidColor(
                if (isLightTheme) Color(0xFFDBDBDB) else Color(0xFF3A3A3A),
            )
        val silhouette =
            SolidColor(
                if (isLightTheme) Color(0xFFFFFFFF) else Color(0xFF8E8E8E),
            )

        return roboBuilder(name = "default-user-avatar") {
            addPath(
                pathData = RobohashAssembler.BACKGROUND,
                fill = background,
            )
            addPath(
                pathData = HEAD,
                fill = silhouette,
            )
            addPath(
                pathData = SHOULDERS,
                fill = silhouette,
            )
        }
    }

    private val HEAD =
        PathData {
            // Circle centered at (150, 112), radius 48
            moveTo(150f, 64f)
            arcToRelative(48f, 48f, 0f, true, true, 0f, 96f)
            arcToRelative(48f, 48f, 0f, true, true, 0f, -96f)
            close()
        }

    private val SHOULDERS =
        PathData {
            // Bust shape from bottom edge up to neck under the head
            moveTo(48f, VIEWPORT_SIZE)
            lineTo(48f, 248f)
            curveTo(48f, 205f, 90f, 180f, 150f, 180f)
            curveTo(210f, 180f, 252f, 205f, 252f, 248f)
            lineTo(252f, VIEWPORT_SIZE)
            close()
        }
}
