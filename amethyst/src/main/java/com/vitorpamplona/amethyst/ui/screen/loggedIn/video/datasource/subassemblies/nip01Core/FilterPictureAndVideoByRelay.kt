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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.video.datasource.subassemblies.nip01Core

import com.vitorpamplona.amethyst.commons.defaults.ASTRO_ALGO_REQ_LIMIT
import com.vitorpamplona.amethyst.commons.defaults.AstroSurface
import com.vitorpamplona.amethyst.commons.defaults.Constants
import com.vitorpamplona.amethyst.commons.defaults.astroAlgoReqTags
import com.vitorpamplona.amethyst.commons.defaults.isAstroAlgoRelay
import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.model.topNavFeeds.relay.RelayTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.service.relays.SincePerRelayMap
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.stories.datasource.filterEphemeralStories
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.datasource.LegacyMimeTypeMap
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.datasource.PictureAndVideoLegacyKinds
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.datasource.PictureVideoAndAudioKinds
import com.vitorpamplona.quartz.experimental.ephemeralStory.EphemeralStoryEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.pool.RelayBasedFilter
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip68Picture.PictureEvent
import com.vitorpamplona.quartz.nip71Video.VideoHorizontalEvent
import com.vitorpamplona.quartz.nip71Video.VideoNormalEvent
import com.vitorpamplona.quartz.nip71Video.VideoShortEvent
import com.vitorpamplona.quartz.nip71Video.VideoVerticalEvent
import com.vitorpamplona.quartz.nip94FileMetadata.FileHeaderEvent

/** Kinds for one AstroRecommend FOR_YOU batch (must match algo-relay forYouKinds). */
private val AstroForYouSurfaceKinds =
    listOf(
        PictureEvent.KIND,
        VideoNormalEvent.KIND,
        VideoShortEvent.KIND,
        FileHeaderEvent.KIND,
        VideoHorizontalEvent.KIND,
        VideoVerticalEvent.KIND,
        EphemeralStoryEvent.KIND,
        TextNoteEvent.KIND,
    )

fun filterPictureAndVideoByRelay(
    relayFilter: RelayTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long? = null,
    account: Account,
): List<RelayBasedFilter> {
    val relayUrl = relayFilter.relayUrl
    val sinceTime = since?.get(relayUrl)?.time ?: defaultSince

    if (relayUrl.isAstroAlgoRelay()) {
        val pullRefresh = account.takeAstroPullRefresh()
        val astroTags = astroAlgoReqTags(pullRefresh, AstroSurface.FOR_YOU)
        val me = Constants.amethystHome
        val meSince = since?.get(me)?.time ?: defaultSince
        // ONE algo REQ — dual kind filters were advancing the ranked offset twice.
        return listOf(
            RelayBasedFilter(
                relay = relayUrl,
                filter =
                    Filter(
                        kinds = AstroForYouSurfaceKinds,
                        limit = ASTRO_ALGO_REQ_LIMIT,
                        tags = astroTags,
                    ),
            ),
        ) +
            pictureAndVideoFilters(me, meSince, 500, null) +
            filterEphemeralStories(relay = me, since = meSince, limit = 500)
    }

    return pictureAndVideoFilters(relayUrl, sinceTime, 100, null)
}

private fun pictureAndVideoFilters(
    relayUrl: com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl,
    sinceTime: Long?,
    limit: Int,
    astroTags: Map<String, List<String>>?,
): List<RelayBasedFilter> =
    listOf(
        RelayBasedFilter(
            relay = relayUrl,
            filter =
                Filter(
                    kinds = PictureVideoAndAudioKinds,
                    limit = limit,
                    since = sinceTime,
                    tags = astroTags,
                ),
        ),
        RelayBasedFilter(
            relay = relayUrl,
            filter =
                Filter(
                    kinds = PictureAndVideoLegacyKinds,
                    tags =
                        buildMap {
                            putAll(LegacyMimeTypeMap)
                            astroTags?.forEach { (k, v) -> put(k, v) }
                        },
                    limit = limit / 2,
                    since = sinceTime,
                ),
        ),
    )
