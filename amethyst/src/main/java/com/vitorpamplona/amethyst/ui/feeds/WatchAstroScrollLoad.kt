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
package com.vitorpamplona.amethyst.ui.feeds

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalInspectionMode
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * When the user nears the end of an interest-ranked feed, request the next slice from
 * algo-relay (`astro=more`). No client-side page cap — the feed grows with cache + limit().
 */
@Composable
fun WatchAstroScrollLoad(
    listState: LazyListState,
    enabled: Boolean,
    accountViewModel: AccountViewModel,
) {
    if (!enabled || LocalInspectionMode.current) return

    LaunchedEffect(listState, enabled) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount to (layout.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.map { (total, lastVisible) ->
            total > 0 && lastVisible >= (total - 3).coerceAtLeast(0)
        }.distinctUntilChanged()
            .filter { it }
            .collect {
                accountViewModel.refreshAstroInterestSubscriptions()
            }
    }
}
