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
    /**
     * Astro owned relay — the ONLY relay this app uses.
     * Self-hosted on the owner's Cloudflare account (nosflare on Workers + D1).
     * Every other constant below points here too, so the whole app is an
     * isolated island: no public Nostr relays are contacted.
     */
    val amethystHome = RelayUrlNormalizer.normalize("wss://astro-relay.hmzzhproxy29127.workers.dev/gw-4ahaAskmWXBPABsWDUzW0E9FTUBmeaSz")

    /** Astro owned media (Blossom) server — images/videos live on the owner's R2. */
    const val ASTRO_MEDIA_BASE = "https://astro-media.hmzzhproxy29127.workers.dev"

    /**
     * Astro algorithmic / trending relay (algo-relay on the PC).
     * Read-only. Exposed via Tailscale Funnel on port 8443 → :3334.
     * See `algo-relay/ASTRO.md`.
     */
    val astroAlgo = RelayUrlNormalizer.normalize("wss://desktop-r082364.tail4b3c94.ts.net:8443/")

    /** HTTPS base for AstroRecommend HTTP APIs (same Funnel host as [astroAlgo]). */
    const val ASTRO_ALGO_HTTP_BASE = "https://desktop-r082364.tail4b3c94.ts.net:8443"

    /**
     * Island mode: ON. The app only ever talks to [amethystHome].
     * All the legacy public-relay constants above already resolve to it, and this
     * switch collapses every aggregated relay set down to the owned relay too.
     */
    const val TEMP_HOME_RELAY_ONLY = true

    fun homeRelayOnly(relays: Set<NormalizedRelayUrl>): Set<NormalizedRelayUrl> = if (TEMP_HOME_RELAY_ONLY) setOf(amethystHome) else relays

    fun homeRelayOnlyMap(filters: Map<NormalizedRelayUrl, List<com.vitorpamplona.quartz.nip01Core.relay.filters.Filter>>): Map<NormalizedRelayUrl, List<com.vitorpamplona.quartz.nip01Core.relay.filters.Filter>> =
        if (!TEMP_HOME_RELAY_ONLY || filters.isEmpty()) {
            filters
        } else {
            mapOf(amethystHome to filters.values.flatten().distinct())
        }

    // —— Owned island: all former public relays now resolve to [amethystHome] ——
    // Keeping the names means every call site (120+ across the app) automatically
    // points at the owner's relay without further edits.
    val nos = amethystHome
    val mom = amethystHome
    val primal = amethystHome
    val wine = amethystHome

    val where = amethystHome
    val elites = amethystHome

    val bitcoiner = amethystHome
    val oxtr = amethystHome

    val nostoday = amethystHome
    val antiprimal = amethystHome
    val ditto = amethystHome

    val auth = amethystHome
    val oxchat = amethystHome

    val news = amethystHome

    val purplepages = amethystHome
    val coracle = amethystHome
    val userkinds = amethystHome
    val yabu = amethystHome
    val nostr1 = amethystHome

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
