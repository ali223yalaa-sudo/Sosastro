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
package com.vitorpamplona.amethyst.ui.dal

import com.vitorpamplona.amethyst.commons.defaults.Constants
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.relay.client.pool.RelayBasedFilter
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtagAlts

/**
 * Astro: Nostr relays have no language or country filter, so a plain `kinds`-only REQ
 * returns whatever the relay holds — in practice mostly non-Arabic content. The client
 * ranking ([AstroRegionRank]) can only reorder notes that already reached the cache, so
 * without this seed the feed stays foreign no matter how good the ranking is.
 *
 * This asks public relays for the hashtags Arabic speakers actually use, which is what
 * pulls Iraqi/Arab posts into the cache to be ranked. It is a seed, not the whole feed:
 * follows, outbox and the personal ME relay still contribute the rest.
 */
fun astroRegionHashtagFilter(
    relay: NormalizedRelayUrl,
    kinds: List<Kind>,
    since: Long?,
    limit: Int,
): RelayBasedFilter =
    RelayBasedFilter(
        relay = relay,
        filter =
            Filter(
                kinds = kinds,
                tags =
                    mapOf(
                        "t" to
                            Constants.REGION_QUERY_HASHTAGS
                                .flatMap(::hashtagAlts)
                                .distinct()
                                .sorted(),
                    ),
                limit = limit,
                since = since,
            ),
    )
