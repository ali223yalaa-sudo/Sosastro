/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.amethyst.ui.feeds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.commons.ui.components.NoteCardSkeleton
import com.vitorpamplona.amethyst.commons.ui.components.ShimmerPlaceholder
import com.vitorpamplona.amethyst.ui.theme.FeedPadding

/**
 * Placeholder cards shown while a feed's first page is loading.
 *
 * The feed opens on `FeedState.Loading` until the first relay page lands, so a
 * blank screen here reads as "broken". A short stack of shimmering cards fills
 * that window and mirrors the shape of the posts that replace it, so the feed
 * looks alive from the first frame instead of flashing empty then filling in.
 */
@Composable
fun LoadingFeedSkeleton(cards: Int = 4) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(FeedPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(cards) {
            NoteCardSkeleton()
        }
    }
}

/**
 * Full-bleed placeholder for the reels feed, whose cards fill the screen.
 * A card-shaped stack would look wrong there, so this mirrors the one-reel
 * layout instead: a large shimmer block over a caption line.
 */
@Composable
fun LoadingReelsSkeleton() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(FeedPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShimmerPlaceholder(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f),
        )
        ShimmerPlaceholder(Modifier.fillMaxWidth(0.5f).height(14.dp))
        ShimmerPlaceholder(Modifier.fillMaxWidth(0.3f).height(12.dp))
    }
}
