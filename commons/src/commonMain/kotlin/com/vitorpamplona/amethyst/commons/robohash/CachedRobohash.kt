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

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Provides the default avatar ImageVector for users without a profile picture.
 *
 * Historically this generated a unique Robohash per pubkey. Astro uses one shared
 * Instagram-style silhouette for everyone instead.
 */
object CachedRobohash {
    fun cached(
        msg: String,
        isLightTheme: Boolean,
    ): ImageVector? = DefaultUserAvatar.get(isLightTheme)

    fun trimToSize(maxItems: Int) {
        // No-op: only two theme variants are retained by DefaultUserAvatar.
    }

    fun get(
        msg: String,
        isLightTheme: Boolean,
    ): ImageVector = DefaultUserAvatar.get(isLightTheme)
}
