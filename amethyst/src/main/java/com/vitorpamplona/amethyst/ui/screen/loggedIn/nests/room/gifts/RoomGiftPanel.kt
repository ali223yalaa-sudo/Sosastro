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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGiftCatalog
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGiftSku
import com.vitorpamplona.amethyst.commons.viewmodels.theme

/**
 * Room gift selection panel — the Compose analogue of TUILiveKit's
 * `GiftListView`. Presented as a modal bottom sheet: a tier tab row,
 * a scrollable gift grid, a quantity stepper and a send button.
 *
 * Unlike the star-wallet [com.vitorpamplona.amethyst.ui.screen.loggedIn.stars.GiftSupportSheet]
 * this panel has no billing backend — sending a gift publishes a
 * kind-7 with a `gift` tag, so the whole flow is offline-friendly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomGiftPanel(
    onSend: (sku: RoomGiftSku, quantity: Int) -> Unit,
    onDismiss: () -> Unit,
    toLabel: String?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val arabic = LocalConfiguration.current.locales[0]?.language == "ar"
    var selectedTier by remember { mutableIntStateOf(RoomGiftCatalog.tiers.first()) }
    var selected by remember { mutableStateOf<RoomGiftSku?>(null) }
    var quantity by remember { mutableIntStateOf(1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF14121C),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = if (arabic) "إرسال هدية" else "Send a gift",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                toLabel?.let {
                    Text(
                        text = if (arabic) "إلى $it" else "to $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            TierRow(
                arabic = arabic,
                selected = selectedTier,
                onSelect = { selectedTier = it },
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(240.dp),
            ) {
                items(RoomGiftCatalog.all.filter { it.tier == selectedTier }, key = { it.id }) { sku ->
                    GiftCell(
                        sku = sku,
                        arabic = arabic,
                        selected = selected?.id == sku.id,
                        onClick = {
                            selected = sku
                            quantity = 1
                        },
                    )
                }
            }

            selected?.let { sku ->
                QuantityRow(
                    arabic = arabic,
                    quantity = quantity,
                    onDec = { quantity = (quantity - 1).coerceAtLeast(1) },
                    onInc = { quantity = (quantity + 1).coerceAtMost(99) },
                )
                SendButton(
                    arabic = arabic,
                    sku = sku,
                    quantity = quantity,
                    onClick = {
                        onSend(sku, quantity)
                        onDismiss()
                    },
                )
            } ?: Text(
                text = if (arabic) "اختر هدية" else "Pick a gift",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun TierRow(
    arabic: Boolean,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoomGiftCatalog.tiers.forEach { tier ->
            val label =
                when (tier) {
                    1 -> if (arabic) "عادية" else "Standard"
                    2 -> if (arabic) "مميزة" else "Premium"
                    3 -> if (arabic) "فخمة" else "Luxe"
                    else -> if (arabic) "أسطورية" else "Legendary"
                }
            val isSelected = tier == selected
            Surface(
                onClick = { onSelect(tier) },
                shape = RoundedCornerShape(50),
                color = if (isSelected) GiftGold else Color.White.copy(alpha = 0.08f),
                contentColor = if (isSelected) Color(0xFF2A1E00) else Color.White,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GiftCell(
    sku: RoomGiftSku,
    arabic: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = remember(sku.id) { sku.theme() }
    val accent = Color(theme.accent)
    val accentDeep = Color(theme.accentDeep)
    Column(
        modifier =
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                .then(
                    if (selected) {
                        Modifier.border(1.5.dp, accent, RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Emoji sits on a soft accent disc so each gift reads with its
        // own colour instead of a bare glyph on a flat tile.
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accentDeep.copy(alpha = 0.25f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = sku.emoji, fontSize = 24.sp)
        }
        Text(
            text = sku.label(arabic),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(
                symbol = MaterialSymbols.Star,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = sku.value.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun QuantityRow(
    arabic: Boolean,
    quantity: Int,
    onDec: () -> Unit,
    onInc: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (arabic) "الكمية" else "Quantity",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton(symbol = MaterialSymbols.Remove, onClick = onDec)
            Text(
                text = quantity.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.Center,
            )
            StepperButton(symbol = MaterialSymbols.Add, onClick = onInc)
        }
    }
}

@Composable
private fun StepperButton(
    symbol: com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbol,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(symbol = symbol, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SendButton(
    arabic: Boolean,
    sku: RoomGiftSku,
    quantity: Int,
    onClick: () -> Unit,
) {
    val label =
        if (arabic) {
            "إرسال ${sku.emoji} × $quantity"
        } else {
            "Send ${sku.emoji} × $quantity"
        }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(GiftGold, GiftGoldDeep))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2A1E00),
            )
        }
    }
}

/** Warm gold used across the gift panel and overlay. */
internal val GiftGold = Color(0xFFFFD54F)
internal val GiftGoldDeep = Color(0xFFFFA726)
