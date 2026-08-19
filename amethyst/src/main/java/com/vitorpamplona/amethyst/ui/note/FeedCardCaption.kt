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
package com.vitorpamplona.amethyst.ui.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.ui.components.TranslatableRichTextViewer
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes

private val OverlayWhite = Color.White
private val OverlayShadow =
    Shadow(
        color = Color.Black.copy(alpha = 0.75f),
        blurRadius = 6f,
    )

/**
 * Instagram-style caption for home cards and reels: two collapsed lines, "show more",
 * auto-translation, and TTS of the text currently on screen (translated when a translation
 * is showing).
 */
@Composable
fun FeedCardCaption(
    title: String?,
    content: String,
    id: String,
    accountViewModel: AccountViewModel,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
    collapsedMaxLines: Int = 2,
) {
    val cleaned = remember(id, content) { captionWithoutMediaUrls(content) }
    if (title == null && cleaned.isBlank()) return

    var expanded by remember(id) { mutableStateOf(false) }
    var hasOverflow by remember(id) { mutableStateOf(false) }

    val titleStyle: TextStyle
    val bodyStyle: TextStyle
    val showMoreStyle: TextStyle
    val ttsTint: Color
    if (overlay) {
        titleStyle =
            TextStyle(
                color = OverlayWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                shadow = OverlayShadow,
            )
        bodyStyle =
            TextStyle(
                color = OverlayWhite.copy(alpha = 0.92f),
                fontSize = 14.sp,
                shadow = OverlayShadow,
            )
        showMoreStyle =
            TextStyle(
                color = OverlayWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                shadow = OverlayShadow,
            )
        ttsTint = OverlayWhite
    } else {
        titleStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        bodyStyle =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        showMoreStyle =
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        ttsTint = Color.Unspecified
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (overlay) 2.dp else 0.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!overlay) {
                Spacer(modifier = Modifier.height(2.dp))
            }
        }

        if (cleaned.isNotBlank()) {
            TranslatableRichTextViewer(
                content = cleaned,
                id = "$id:caption",
                accountViewModel = accountViewModel,
            ) { displayText ->
                Text(
                    text = displayText,
                    style = bodyStyle,
                    maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        hasOverflow = result.hasVisualOverflow
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (hasOverflow || expanded) {
                        Text(
                            text = stringRes(if (expanded) R.string.collapse else R.string.show_more),
                            modifier = Modifier.clickable { expanded = !expanded },
                            style = showMoreStyle,
                        )
                    }
                    DrawPlayName(
                        name = displayText,
                        playbackId = id,
                        tint = ttsTint,
                    )
                }
            }
        }
    }
}
