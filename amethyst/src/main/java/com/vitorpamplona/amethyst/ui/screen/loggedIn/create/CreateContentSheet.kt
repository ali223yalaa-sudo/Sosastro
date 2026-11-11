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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbol
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.Size22Modifier
import com.vitorpamplona.amethyst.ui.theme.placeholderText

/**
 * The kind of content the user wants to create. Each entry maps to a real
 * publishing path in the app; the sheet only picks the path.
 */
enum class CreateContentType(
    val labelRes: Int,
    val descriptionRes: Int,
    val symbol: MaterialSymbol,
) {
    POST(R.string.create_content_post, R.string.create_content_post_desc, MaterialSymbols.PhotoLibrary),
    REEL(R.string.create_content_reel, R.string.create_content_reel_desc, MaterialSymbols.Movie),
    STORY(R.string.create_content_story, R.string.create_content_story_desc, MaterialSymbols.AutoAwesome),
    INSTANT(R.string.create_content_instant, R.string.create_content_instant_desc, MaterialSymbols.Bolt),
    LIVE(R.string.create_content_live, R.string.create_content_live_desc, MaterialSymbols.LiveTv),
}

/**
 * Instagram-style "Create" drawer. The header is a title plus a close button,
 * then one row per content type (icon tile + label + one-line description) and a
 * footer row of quick actions (camera, gallery, text post, drafts).
 *
 * Every callback hands control back to the caller so the caller can start the
 * matching real flow (camera, gallery picker, composer, live setup).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateContentSheet(
    onDismiss: () -> Unit,
    onPickType: (CreateContentType) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onTextPost: () -> Unit,
    onDrafts: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringRes(R.string.create_content_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        symbol = MaterialSymbols.Close,
                        contentDescription = stringRes(R.string.create_close),
                        modifier = Size22Modifier,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            CreateContentType.entries.forEach { type ->
                ContentTypeRow(type = type, onClick = { onPickType(type) })
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                QuickAction(MaterialSymbols.CameraAlt, R.string.create_content_camera, onCamera)
                QuickAction(MaterialSymbols.PhotoLibrary, R.string.create_content_gallery, onGallery)
                QuickAction(MaterialSymbols.TextFields, R.string.create_content_text_post, onTextPost)
                QuickAction(MaterialSymbols.Drafts, R.string.create_content_drafts, onDrafts)
            }
        }
    }
}

@Composable
private fun ContentTypeRow(
    type: CreateContentType,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                symbol = type.symbol,
                contentDescription = null,
                modifier = Size22Modifier,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.size(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringRes(type.labelRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringRes(type.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.placeholderText,
            )
        }
    }
}

@Composable
private fun QuickAction(
    symbol: MaterialSymbol,
    labelRes: Int,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .size(54.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                symbol = symbol,
                contentDescription = stringRes(labelRes),
                modifier = Size22Modifier,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringRes(labelRes),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
