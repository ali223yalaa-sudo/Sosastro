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

import com.vitorpamplona.quartz.nip28PublicChat.list.tags.ChannelTag
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayInfo
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayType

/**
 * Default relay/channel bundles applied to every freshly-created Amethyst
 * account. Both the Android UI (`AccountSessionManager.createNewAccount`)
 * and the `amy` CLI (`amy create`) seed new accounts from these so users
 * land in the same connected state regardless of which client they start
 * with.
 *
 * Pure data — no platform deps, no runtime config, no i18n. Change here,
 * both clients follow.
 *
 * [DefaultChannels] starts empty (no automatic public chat groups).
 * [DefaultNIP65RelaySet] puts ME primary first, then Europe public relays.
 */
val DefaultChannels = emptyList<ChannelTag>()

// Island mode: every default list below collapses to the single owned relay.
val DefaultNIP65RelaySet = setOf(Constants.amethystHome)

val DefaultNIP65List =
    listOf(
        AdvertisedRelayInfo(Constants.amethystHome, AdvertisedRelayType.BOTH),
    )

val DefaultGlobalRelays = listOf(Constants.amethystHome)

/**
 * DM inbox (kind 10050): only the owned relay, so Astro users land on the same
 * relay without manual inbox setup and no DMs leak to public relays.
 */
val DefaultDMRelayList = listOf(Constants.amethystHome)

val DefaultSearchRelayList = setOf(Constants.amethystHome)

val DefaultIndexerRelayList = setOf(Constants.amethystHome)
