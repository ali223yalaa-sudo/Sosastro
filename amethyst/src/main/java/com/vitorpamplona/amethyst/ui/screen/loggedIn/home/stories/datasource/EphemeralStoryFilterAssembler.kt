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

import com.vitorpamplona.amethyst.commons.relayClient.composeSubscriptionManagers.ComposeSubscriptionManager
import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.model.TopFilter
import com.vitorpamplona.amethyst.model.User
import com.vitorpamplona.amethyst.model.topNavFeeds.allFollows.AllFollowsTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.aroundMe.LocationTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.global.GlobalTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.hashtag.HashtagTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.noteBased.allcommunities.AllCommunitiesTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.noteBased.author.AuthorsTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.noteBased.community.SingleCommunityTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.noteBased.muted.MutedAuthorsTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.model.topNavFeeds.relay.RelayTopNavPerRelayFilterSet
import com.vitorpamplona.amethyst.service.relayClient.eoseManagers.PerUserAndFollowListEoseManager
import com.vitorpamplona.amethyst.service.relays.SincePerRelayMap
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountFeedContentStates
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.pool.RelayBasedFilter
import com.vitorpamplona.quartz.nip01Core.relay.client.subscriptions.Subscription
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

class EphemeralStoryQueryState(
    val account: Account,
    val feedState: AccountFeedContentStates,
    val scope: CoroutineScope,
)

class EphemeralStoryFilterAssembler(
    client: INostrClient,
) : ComposeSubscriptionManager<EphemeralStoryQueryState>() {
    val group =
        listOf(
            EphemeralStoryOutboxEventsFilterSubAssembler(client, ::allKeys),
        )

    override fun invalidateKeys() = invalidateFilters()

    override fun invalidateFilters() = group.forEach { it.invalidateFilters() }

    override fun destroy() = group.forEach { it.destroy() }
}

class EphemeralStoryOutboxEventsFilterSubAssembler(
    client: INostrClient,
    allKeys: () -> Set<EphemeralStoryQueryState>,
) : PerUserAndFollowListEoseManager<EphemeralStoryQueryState, TopFilter>(client, allKeys) {
    override fun updateFilter(
        key: EphemeralStoryQueryState,
        since: SincePerRelayMap?,
    ): List<RelayBasedFilter>? {
        val feedSettings = key.followsPerRelay()
        // Always prefer last 24h for ephemeral stories; EOSE since can tighten further.
        val defaultSince = TimeUtils.oneDayAgo()
        return when (feedSettings) {
            is AllCommunitiesTopNavPerRelayFilterSet -> filterEphemeralStoriesByAllCommunities(feedSettings, since, defaultSince)
            is AllFollowsTopNavPerRelayFilterSet -> filterEphemeralStoriesByFollows(feedSettings, since, defaultSince)
            is AuthorsTopNavPerRelayFilterSet -> filterEphemeralStoriesByAuthors(feedSettings, since, defaultSince)
            is GlobalTopNavPerRelayFilterSet -> filterEphemeralStoriesGlobal(feedSettings, since, defaultSince)
            is HashtagTopNavPerRelayFilterSet -> filterEphemeralStoriesByHashtag(feedSettings, since, defaultSince)
            is LocationTopNavPerRelayFilterSet -> filterEphemeralStoriesByGeohash(feedSettings, since, defaultSince)
            is MutedAuthorsTopNavPerRelayFilterSet -> filterEphemeralStoriesByMutedAuthors(feedSettings, since, defaultSince)
            is SingleCommunityTopNavPerRelayFilterSet -> filterEphemeralStoriesByCommunity(feedSettings, since, defaultSince)
            is RelayTopNavPerRelayFilterSet -> filterEphemeralStoriesByRelay(feedSettings, since, defaultSince)
            else -> emptyList()
        }
    }

    override fun user(key: EphemeralStoryQueryState) = key.account.userProfile()

    override fun list(key: EphemeralStoryQueryState) = key.listName()

    fun EphemeralStoryQueryState.listNameFlow() = account.settings.defaultHomeFollowList

    fun EphemeralStoryQueryState.listName() = listNameFlow().value

    fun EphemeralStoryQueryState.followsPerRelayFlow() = account.liveHomeFollowListsPerRelay

    fun EphemeralStoryQueryState.followsPerRelay() = followsPerRelayFlow().value

    val userJobMap = mutableMapOf<User, List<Job>>()

    @OptIn(FlowPreview::class)
    override fun newSub(key: EphemeralStoryQueryState): Subscription {
        val user = user(key)
        userJobMap[user]?.forEach { it.cancel() }
        userJobMap[user] =
            listOf(
                key.scope.launch(Dispatchers.IO) {
                    key.followsPerRelayFlow().sample(1000).collectLatest {
                        invalidateFilters()
                    }
                },
                key.scope.launch(Dispatchers.IO) {
                    key.feedState.ephemeralStoriesFeed.lastNoteCreatedAtWhenFullyLoaded.sample(1000).collectLatest {
                        invalidateFilters()
                    }
                },
            )

        return super.newSub(key)
    }

    override fun endSub(
        key: User,
        subId: String,
    ) {
        super.endSub(key, subId)
        userJobMap[key]?.forEach { it.cancel() }
    }
}
