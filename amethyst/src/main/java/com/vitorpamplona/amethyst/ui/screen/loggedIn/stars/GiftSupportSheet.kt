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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.stars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.model.stars.GiftSku
import com.vitorpamplona.amethyst.ui.note.effects.GiftLaunchBurst
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftSupportSheet(
    accountViewModel: AccountViewModel,
    toPubkey: String?,
    noteId: String?,
    onDismiss: () -> Unit,
    onSent: (emoji: String) -> Unit = {},
    onOpenStore: () -> Unit,
) {
    val starsState = accountViewModel.account.starsWallet
    val snapshot by starsState.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val arabic = LocalConfiguration.current.locales[0]?.language == "ar"
    var busySku by remember { mutableStateOf<String?>(null) }
    var messageRes by remember { mutableStateOf<Int?>(null) }
    var launchEmoji by remember { mutableStateOf<String?>(null) }
    val myPub = accountViewModel.userProfile().pubkeyHex
    val self = toPubkey != null && toPubkey.equals(myPub, ignoreCase = true)

    LaunchedEffect(Unit) {
        starsState.refresh()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringRes(R.string.stars_gift_send),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StarsCountBadge(stars = snapshot.stars)
                }
                Text(
                    text = stringRes(R.string.stars_gift_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                messageRes?.let {
                    Text(
                        text = stringRes(it),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                if (toPubkey.isNullOrBlank()) {
                    Text(stringRes(R.string.stars_gift_no_author), color = MaterialTheme.colorScheme.error)
                } else if (self) {
                    Text(stringRes(R.string.stars_gift_self), color = MaterialTheme.colorScheme.error)
                } else {
                    val gifts = snapshot.catalog?.gifts.orEmpty()
                    if (gifts.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 108.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(gifts, key = { it.id }) { gift ->
                                val owned = snapshot.inventory[gift.id] ?: 0
                                GiftSendCard(
                                    gift = gift,
                                    owned = owned,
                                    arabic = arabic,
                                    busy = busySku == gift.id,
                                    enabled = busySku == null,
                                    onSend = {
                                        busySku = gift.id
                                        scope.launch {
                                            val result = starsState.sendGift(gift.id, toPubkey, noteId)
                                            when {
                                                result.isSuccess -> {
                                                    val target = noteId?.let { LocalCache.getNoteIfExists(it) }
                                                    if (target != null) {
                                                        accountViewModel.reactOnce(target, gift.emoji)
                                                    }
                                                    launchEmoji = gift.emoji
                                                    delay(720)
                                                    onSent(gift.emoji)
                                                    onDismiss()
                                                }
                                                result.exceptionOrNull()?.message == "insufficient" -> {
                                                    messageRes = R.string.stars_store_need_stars
                                                    busySku = null
                                                }
                                                result.exceptionOrNull()?.message == "self" -> {
                                                    messageRes = R.string.stars_gift_self
                                                    busySku = null
                                                }
                                                else -> {
                                                    messageRes = R.string.stars_store_error
                                                    busySku = null
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                TextButton(onClick = onOpenStore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.stars_store_open))
                }
                Spacer(Modifier.height(8.dp))
            }
            launchEmoji?.let { emoji ->
                GiftLaunchBurst(
                    emoji = emoji,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun GiftSendCard(
    gift: GiftSku,
    owned: Int,
    arabic: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(gift.emoji, fontSize = 34.sp)
            Text(
                text = if (arabic) gift.labelAr else gift.labelEn,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text =
                    if (owned > 0) {
                        stringRes(R.string.stars_store_owned, owned)
                    } else {
                        "${gift.starPrice} ${stringRes(R.string.stars_unit)}"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Button(onClick = onSend, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringRes(R.string.stars_gift_send_one))
                }
            }
        }
    }
}
