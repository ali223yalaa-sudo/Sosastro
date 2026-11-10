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
package com.vitorpamplona.amethyst.commons.defaults

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtag
import com.vitorpamplona.quartz.nip32Labeling.label
import com.vitorpamplona.quartz.nip32Labeling.labelNamespace

/**
 * Relay URLs that Amethyst (and `amy`) seed new accounts with and use as
 * sensible defaults across the app. Pure data — lives in commons so JVM /
 * iOS / desktop builds get the same URLs without duplicating the list.
 *
 * Astro: [amethystHome] is the Middle-East primary relay and is always included
 * in bootstrap / DM / outbox defaults alongside public (Europe) relays.
 */
object Constants {
    /** Middle-East primary relay (PC strfry via Tailscale Funnel). */
    val amethystHome = RelayUrlNormalizer.normalize("wss://desktop-r082364.tail4b3c94.ts.net/")

    /**
     * Astro algorithmic / trending relay (algo-relay on the PC).
     * Read-only. Exposed via Tailscale Funnel on port 8443 → :3334.
     * See `algo-relay/ASTRO.md`.
     */
    val astroAlgo = RelayUrlNormalizer.normalize("wss://desktop-r082364.tail4b3c94.ts.net:8443/")

    /** HTTPS base for AstroRecommend HTTP APIs (same Funnel host as [astroAlgo]). */
    const val ASTRO_ALGO_HTTP_BASE = "https://desktop-r082364.tail4b3c94.ts.net:8443"

    /**
     * Legacy kill-switch. Must stay false so public relays work as EU backup.
     * Kept for call sites in [homeRelayOnly] / [homeRelayOnlyMap].
     */
    const val TEMP_HOME_RELAY_ONLY = false

    fun homeRelayOnly(relays: Set<NormalizedRelayUrl>): Set<NormalizedRelayUrl> = if (TEMP_HOME_RELAY_ONLY) setOf(amethystHome) else relays

    fun homeRelayOnlyMap(filters: Map<NormalizedRelayUrl, List<com.vitorpamplona.quartz.nip01Core.relay.filters.Filter>>): Map<NormalizedRelayUrl, List<com.vitorpamplona.quartz.nip01Core.relay.filters.Filter>> =
        if (!TEMP_HOME_RELAY_ONLY || filters.isEmpty()) {
            filters
        } else {
            mapOf(amethystHome to filters.values.flatten().distinct())
        }

    // —— Public / Europe defaults (upstream Amethyst) ——
    val nos = RelayUrlNormalizer.normalize("wss://nos.lol")
    val mom = RelayUrlNormalizer.normalize("wss://nostr.mom")
    val primal = RelayUrlNormalizer.normalize("wss://relay.primal.net")
    val wine = RelayUrlNormalizer.normalize("wss://nostr.wine")

    val where = RelayUrlNormalizer.normalize("wss://relay.noswhere.com")
    val elites = RelayUrlNormalizer.normalize("wss://nostrelites.org")

    val bitcoiner = RelayUrlNormalizer.normalize("wss://nostr.bitcoiner.social")
    val oxtr = RelayUrlNormalizer.normalize("wss://nostr.oxtr.dev")

    val nostoday = RelayUrlNormalizer.normalize("wss://search.nos.today")
    val antiprimal = RelayUrlNormalizer.normalize("wss://antiprimal.net")
    val ditto = RelayUrlNormalizer.normalize("wss://relay.ditto.pub")

    val auth = RelayUrlNormalizer.normalize("wss://auth.nostr1.com")
    val oxchat = RelayUrlNormalizer.normalize("wss://relay.0xchat.com")

    val news = RelayUrlNormalizer.normalize("wss://news.utxo.one")

    val purplepages = RelayUrlNormalizer.normalize("wss://purplepag.es")
    val coracle = RelayUrlNormalizer.normalize("wss://indexer.coracle.social")
    val userkinds = RelayUrlNormalizer.normalize("wss://user.kindpag.es")
    val yabu = RelayUrlNormalizer.normalize("wss://directory.yabu.me")
    val nostr1 = RelayUrlNormalizer.normalize("wss://profiles.nostr1.com")

    /** Alias: Middle-East primary. */
    val middleEastRelay: NormalizedRelayUrl = amethystHome

    /**
     * Europe / public backup set used when ME is down and for Global→Europe feed.
     * Does not include [amethystHome].
     */
    val europeRelays: Set<NormalizedRelayUrl> =
        setOf(nos, mom, primal, wine, bitcoiner, oxtr, news)

    /**
     * ME + Europe — publish targets so ME posts are mirrored for backup.
     */
    val middleEastPublishRelays: Set<NormalizedRelayUrl> = setOf(amethystHome) + europeRelays

    /**
     * Relays to query for Middle-East feed: prefer ME, fall back to Europe copies
     * filtered by [REGION_HASHTAG].
     */
    val middleEastReadRelays: Set<NormalizedRelayUrl> = setOf(amethystHome) + europeRelays

    val bootstrapInbox = setOf(amethystHome, primal, mom, nos, bitcoiner, oxtr, yabu)
    val eventFinderRelays = setOf(amethystHome, wine, primal, mom, nos, bitcoiner, oxtr)

    // —— Region tagging (not GPS) ——
    const val REGION_NAMESPACE = "astro.region"
    const val REGION_MIDDLE_EAST = "middle-east"

    /** Hashtag used for REQ `#t` filters (widely supported). */
    const val REGION_HASHTAG = "middle-east"

    /**
     * Hashtags used as a `#t` REQ seed so public relays actually return Arab content.
     * Nostr has no language filter, so the only way to pull Arabic posts into the
     * cache (and therefore into [AstroRegionRank]) is to ask for the hashtags Arabic
     * speakers use. Kept small — this is a seed, the rest arrives via follows/outbox.
     */
    val REGION_QUERY_HASHTAGS: Set<String> =
        setOf(
            "middle-east",
            "arabic",
            "arab",
            "iraq",
            "iraqi",
            "baghdad",
            "basra",
            "mosul",
            "erbil",
            "kurdistan",
            "العراق",
            "بغداد",
            "الموصل",
            "البصرة",
            "العربية",
            "السعودية",
            "مصر",
            "الخليج",
            "سوريا",
            "فلسطين",
            "اليمن",
            "الأردن",
            "لبنان",
            "الجزائر",
        )
}

/** Attach Middle-East region tags so EU relays can filter ME copies later. */
fun <T : Event> TagArrayBuilder<T>.attachMiddleEastRegion() {
    hashtag(Constants.REGION_HASHTAG)
    labelNamespace(Constants.REGION_NAMESPACE)
    label(Constants.REGION_MIDDLE_EAST, Constants.REGION_NAMESPACE)
}
