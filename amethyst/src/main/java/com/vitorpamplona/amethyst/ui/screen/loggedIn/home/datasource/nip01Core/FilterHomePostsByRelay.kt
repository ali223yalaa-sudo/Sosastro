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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.home.datasource.nip01Core

import com.vitorpamplona.amethyst.commons.defaults.ASTRO_ALGO_REQ_LIMIT
import com.vitorpamplona.amethyst.commons.defaults.AstroSurface
import com.vitorpamplona.amethyst.commons.defaults.Constants
import com.vitorpamplona.amethyst.commons.defaults.astroAlgoReqTags
import com.vitorpamplona.amethyst.commons.defaults.isAstroAlgoRelay
import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.model.topNavFeeds.relay.RelayTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.service.relays.SincePerRelayMap
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.datasource.nip65Follows.HomePostsConversationKinds
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.datasource.nip65Follows.HomePostsNewThreadKinds1
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.datasource.nip65Follows.HomePostsNewThreadKinds2
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.datasource.PictureAndVideoKinds
import com.vitorpamplona.quartz.nip01Core.relay.client.pool.RelayBasedFilter
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip18Reposts.GenericRepostEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip23LongContent.LongTextNoteEvent
import com.vitorpamplona.quartz.nip68Picture.PictureEvent

/** Kinds for one AstroRecommend POSTS batch (must match algo-relay postsKinds). */
private val AstroPostsSurfaceKinds =
    listOf(
        TextNoteEvent.KIND,
        RepostEvent.KIND,
        GenericRepostEvent.KIND,
        LongTextNoteEvent.KIND,
    )

fun filterHomePostsByRelay(
    relayFilter: RelayTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    newThreadSince: Long?,
    repliesSince: Long?,
    account: Account,
): List<RelayBasedFilter> {
    val relayUrl = relayFilter.relayUrl
    val sinceTime = since?.get(relayUrl)?.time

    // AstroRecommend: ONE REQ per surface. Multiple kind-split REQs each advanced the
    // server offset and left the UI with 0–1 items after kind filtering.
    if (relayUrl.isAstroAlgoRelay()) {
        val pullRefresh = account.takeAstroPullRefresh()
        val astroTags = astroAlgoReqTags(pullRefresh, AstroSurface.POSTS)
        val me = Constants.amethystHome
        val meSince = since?.get(me)?.time ?: newThreadSince
        return listOf(
            RelayBasedFilter(
                relay = relayUrl,
                filter =
                    Filter(
                        kinds = AstroPostsSurfaceKinds,
                        limit = ASTRO_ALGO_REQ_LIMIT,
                        tags = astroTags,
                    ),
            ),
            RelayBasedFilter(
                relay = me,
                filter =
                    Filter(
                        kinds = HomePostsNewThreadKinds1,
                        limit = 500,
                        since = meSince,
                    ),
            ),
            RelayBasedFilter(
                relay = me,
                filter =
                    Filter(
                        kinds = HomePostsNewThreadKinds2,
                        limit = 100,
                        since = meSince,
                    ),
            ),
            RelayBasedFilter(
                relay = me,
                filter =
                    Filter(
                        kinds = listOf(PictureEvent.KIND) + PictureAndVideoKinds,
                        limit = 500,
                        since = meSince,
                    ),
            ),
        )
    }

    return listOf(
        RelayBasedFilter(
            relay = relayUrl,
            filter =
                Filter(
                    kinds = HomePostsNewThreadKinds1,
                    limit = 500,
                    since = sinceTime ?: newThreadSince,
                ),
        ),
        RelayBasedFilter(
            relay = relayUrl,
            filter =
                Filter(
                    kinds = HomePostsNewThreadKinds2,
                    limit = 100,
                    since = sinceTime ?: newThreadSince,
                ),
        ),
        RelayBasedFilter(
            relay = relayUrl,
            filter =
                Filter(
                    kinds = HomePostsConversationKinds,
                    limit = 500,
                    since = sinceTime ?: repliesSince,
                ),
        ),
    )
}
