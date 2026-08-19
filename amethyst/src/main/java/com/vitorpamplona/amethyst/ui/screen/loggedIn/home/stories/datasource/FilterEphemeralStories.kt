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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.home.stories.datasource

import com.vitorpamplona.amethyst.commons.defaults.Constants
import com.vitorpamplona.amethyst.commons.defaults.isAstroAlgoRelay
import com.vitorpamplona.amethyst.model.topNavFeeds.allFollows.AllFollowsTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.aroundMe.LocationTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.global.GlobalTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.hashtag.HashtagTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.noteBased.allcommunities.AllCommunitiesTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.noteBased.author.AuthorsTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.noteBased.community.SingleCommunityTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.noteBased.muted.MutedAuthorsTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.relay.RelayTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.service.relays.SincePerRelayMap
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.pool.RelayBasedFilter
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlin.math.max

fun filterEphemeralStories(
    relay: NormalizedRelayUrl,
    authors: Set<HexKey>? = null,
    tags: Map<String, List<String>>? = null,
    since: Long? = null,
    limit: Int = 200,
): List<RelayBasedFilter> {
    val authorList = authors?.sorted()
    return listOf(
        RelayBasedFilter(
            relay = relay,
            filter =
                Filter(
                    authors = authorList,
                    kinds = EphemeralStoryKinds,
                    tags = tags,
                    limit = if (since == null) max(limit, (authorList?.size ?: 1) * 10) else null,
                    since = since,
                ),
        ),
    )
}

fun filterEphemeralStoriesGlobal(
    relays: GlobalTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    if (relays.set.isEmpty()) return emptyList()
    return relays.set.flatMap {
        filterEphemeralStories(
            relay = it.key,
            since = since?.get(it.key)?.time ?: defaultSince,
            limit = 100,
        )
    }
}

fun filterEphemeralStoriesByRelay(
    relayFilter: RelayTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    val relayUrl =
        if (relayFilter.relayUrl.isAstroAlgoRelay()) {
            Constants.amethystHome
        } else {
            relayFilter.relayUrl
        }
    return filterEphemeralStories(
        relay = relayUrl,
        since = since?.get(relayUrl)?.time ?: defaultSince,
        limit = 150,
    )
}

fun filterEphemeralStoriesByAuthors(
    authorSet: AuthorsTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    if (authorSet.set.isEmpty()) return emptyList()
    return authorSet.set.flatMap { entry ->
        if (entry.value.authors.isEmpty()) {
            emptyList()
        } else {
            filterEphemeralStories(
                relay = entry.key,
                authors = entry.value.authors,
                since = since?.get(entry.key)?.time ?: defaultSince,
            )
        }
    }
}

fun filterEphemeralStoriesByMutedAuthors(
    authorSet: MutedAuthorsTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    if (authorSet.set.isEmpty()) return emptyList()
    return authorSet.set.flatMap { entry ->
        if (entry.value.authors.isEmpty()) {
            emptyList()
        } else {
            filterEphemeralStories(
                relay = entry.key,
                authors = entry.value.authors,
                since = since?.get(entry.key)?.time ?: defaultSince,
            )
        }
    }
}

fun filterEphemeralStoriesByHashtag(
    hashtagSet: HashtagTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    if (hashtagSet.set.isEmpty()) return emptyList()
    return hashtagSet.set.flatMap { entry ->
        val tags = entry.value.hashtags
        if (tags.isEmpty()) {
            emptyList()
        } else {
            filterEphemeralStories(
                relay = entry.key,
                tags = mapOf("t" to tags.sorted()),
                since = since?.get(entry.key)?.time ?: defaultSince,
            )
        }
    }
}

fun filterEphemeralStoriesByGeohash(
    locationSet: LocationTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    if (locationSet.set.isEmpty()) return emptyList()
    return locationSet.set.flatMap { entry ->
        val geotags = entry.value.geotags
        if (geotags.isEmpty()) {
            emptyList()
        } else {
            filterEphemeralStories(
                relay = entry.key,
                tags = mapOf("g" to geotags.sorted()),
                since = since?.get(entry.key)?.time ?: defaultSince,
            )
        }
    }
}

fun filterEphemeralStoriesByFollows(
    followsSet: AllFollowsTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    if (followsSet.set.isEmpty()) return emptyList()
    return followsSet.set.flatMap { entry ->
        val sinceTime = since?.get(entry.key)?.time ?: defaultSince
        val relay = entry.key
        buildList {
            entry.value.authors?.takeIf { it.isNotEmpty() }?.let {
                addAll(filterEphemeralStories(relay, authors = it, since = sinceTime))
            }
            entry.value.geotags?.takeIf { it.isNotEmpty() }?.let {
                addAll(filterEphemeralStories(relay, tags = mapOf("g" to it.sorted()), since = sinceTime))
            }
            entry.value.hashtags?.takeIf { it.isNotEmpty() }?.let {
                addAll(filterEphemeralStories(relay, tags = mapOf("t" to it.sorted()), since = sinceTime))
            }
            entry.value.communities?.takeIf { it.isNotEmpty() }?.let { communities ->
                communities.forEach { community ->
                    addAll(
                        filterEphemeralStories(
                            relay = relay,
                            tags = mapOf("a" to listOf(community)),
                            since = sinceTime,
                        ),
                    )
                }
            }
        }
    }
}

fun filterEphemeralStoriesByCommunity(
    communitySet: SingleCommunityTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    if (communitySet.set.isEmpty()) return emptyList()
    return communitySet.set.flatMap { entry ->
        filterEphemeralStories(
            relay = entry.key,
            authors = entry.value.authors,
            tags = mapOf("a" to listOf(entry.value.community)),
            since = since?.get(entry.key)?.time ?: defaultSince,
        )
    }
}

fun filterEphemeralStoriesByAllCommunities(
    communitiesSet: AllCommunitiesTopNavPerRelayFilterSet,
    since: SincePerRelayMap?,
    defaultSince: Long?,
): List<RelayBasedFilter> {
    if (communitiesSet.set.isEmpty()) return emptyList()
    return communitiesSet.set.flatMap { entry ->
        val communities = entry.value.communities
        if (communities.isEmpty()) {
            emptyList()
        } else {
            filterEphemeralStories(
                relay = entry.key,
                tags = mapOf("a" to communities.sorted()),
                since = since?.get(entry.key)?.time ?: defaultSince,
            )
        }
    }
}
