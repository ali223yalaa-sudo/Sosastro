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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.model.stars.GiftSku
import com.vitorpamplona.amethyst.model.stars.StarPackage
import com.vitorpamplona.amethyst.ui.navigation.bottombars.AppBottomBar
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarsStoreScreen(
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val starsState = accountViewModel.account.starsWallet
    val snapshot by starsState.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busySku by remember { mutableStateOf<String?>(null) }
    var messageRes by remember { mutableStateOf<Int?>(null) }
    val arabic = LocalConfiguration.current.locales[0]?.language == "ar"

    LaunchedEffect(Unit) {
        starsState.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            symbol = MaterialSymbols.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringRes(R.string.stars_store_title), fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    if (nav.canPop()) {
                        IconButton(onClick = { nav.popBack() }) {
                            Icon(
                                symbol = MaterialSymbols.AutoMirrored.ArrowBack,
                                contentDescription = stringRes(R.string.back),
                            )
                        }
                    }
                },
                actions = {
                    StarsCountBadge(stars = snapshot.stars)
                    Spacer(Modifier.size(12.dp))
                },
            )
        },
        bottomBar = {
            AppBottomBar(Route.Wallet, nav, accountViewModel) { route ->
                if (route != Route.Wallet) {
                    nav.navBottomBar(route)
                }
            }
        },
    ) { padding ->
        val catalog = snapshot.catalog
        if (catalog == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DailyStarGiftCard(
                    stars = snapshot.dailyStars,
                    claimed = snapshot.dailyClaimed,
                    busy = busySku == "daily",
                    enabled = busySku == null && !snapshot.dailyClaimed,
                    onClaim = {
                        busySku = "daily"
                        scope.launch {
                            val result = starsState.claimDailyGift()
                            messageRes =
                                when {
                                    result.isSuccess -> R.string.stars_daily_claimed
                                    result.exceptionOrNull()?.message == "already_claimed" ->
                                        R.string.stars_daily_already
                                    else -> R.string.stars_store_error
                                }
                            busySku = null
                        }
                    },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringRes(R.string.stars_store_buy_stars),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringRes(R.string.stars_store_payment_later),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    messageRes?.let {
                        Text(stringRes(it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            items(catalog.packages, key = { it.id }) { pack ->
                StarPackageCard(
                    pack = pack,
                    enabled = busySku == null,
                    onClick = {
                        busySku = pack.id
                        scope.launch {
                            val result = starsState.requestStarPackage(pack.id)
                            messageRes =
                                if (result.isSuccess) {
                                    R.string.stars_store_order_pending
                                } else {
                                    R.string.stars_store_error
                                }
                            busySku = null
                        }
                    },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringRes(R.string.stars_store_gifts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(catalog.gifts, key = { it.id }) { gift ->
                val owned = snapshot.inventory[gift.id] ?: 0
                GiftShopCard(
                    gift = gift,
                    owned = owned,
                    arabic = arabic,
                    busy = busySku == gift.id,
                    enabled = busySku == null,
                    onBuy = {
                        busySku = gift.id
                        scope.launch {
                            val result = starsState.buyGift(gift.id)
                            messageRes =
                                when {
                                    result.isSuccess -> R.string.stars_store_bought
                                    result.exceptionOrNull()?.message == "insufficient" ->
                                        R.string.stars_store_need_stars
                                    else -> R.string.stars_store_error
                                }
                            busySku = null
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DailyStarGiftCard(
    stars: Int,
    claimed: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClaim: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (claimed) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🎁", fontSize = 32.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringRes(R.string.stars_daily_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringRes(R.string.stars_daily_hint, stars),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onClaim, enabled = enabled && !busy && !claimed) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        if (claimed) {
                            stringRes(R.string.stars_daily_done)
                        } else {
                            stringRes(R.string.stars_daily_claim)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun StarsCountBadge(stars: Int) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            symbol = MaterialSymbols.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stars.toString(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun StarPackageCard(
    pack: StarPackage,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⭐", fontSize = 28.sp)
            Text("${pack.stars}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                text = stringRes(R.string.stars_unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringRes(R.string.stars_store_select_pack))
            }
        }
    }
}

@Composable
private fun GiftShopCard(
    gift: GiftSku,
    owned: Int,
    arabic: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onBuy: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(gift.emoji, fontSize = 32.sp)
            Text(
                text = if (arabic) gift.labelAr else gift.labelEn,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "${gift.starPrice} ${stringRes(R.string.stars_unit)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (owned > 0) {
                Text(
                    text = stringRes(R.string.stars_store_owned, owned),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = onBuy, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringRes(R.string.stars_store_buy_gift))
                }
            }
        }
    }
}

@Composable
fun StarsTopBarChip(
    accountViewModel: AccountViewModel,
    onClick: () -> Unit,
) {
    val snapshot by accountViewModel.account.starsWallet.snapshot
        .collectAsStateWithLifecycle()
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            symbol = MaterialSymbols.Star,
            contentDescription = stringRes(R.string.stars_store_title),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = snapshot.stars.toString(),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
