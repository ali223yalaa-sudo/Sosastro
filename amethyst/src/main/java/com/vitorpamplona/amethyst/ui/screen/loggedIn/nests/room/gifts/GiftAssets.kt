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

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decode a bundled gift icon once and cache it in composition. Returns
 * `null` while the (small) asset decodes or if it is missing, so callers
 * can fall back to the emoji glyph.
 */
@Composable
fun rememberGiftIcon(iconPath: String?): ImageBitmap? {
    if (iconPath == null) return null
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, iconPath) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(iconPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                }.getOrNull()
            }
    }
    return icon
}

/**
 * The real per-gift icon (rendered from upstream Twemoji vectors),
 * falling back to the emoji glyph until the asset is ready so the cell
 * never flashes empty.
 */
@Composable
fun GiftIcon(
    iconPath: String?,
    emoji: String,
    size: Dp,
    emojiSize: Int,
    modifier: Modifier = Modifier,
) {
    val icon = rememberGiftIcon(iconPath)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        } else {
            Text(text = emoji, fontSize = emojiSize.sp)
        }
    }
}
