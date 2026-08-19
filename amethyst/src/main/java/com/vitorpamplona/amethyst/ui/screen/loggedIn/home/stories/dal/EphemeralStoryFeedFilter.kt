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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.home.stories.dal

import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.model.AddressableNote
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.model.TopFilter
import com.vitorpamplona.amethyst.model.filterIntoSet
import com.vitorpamplona.amethyst.ui.dal.AdditiveFeedFilter
import com.vitorpamplona.amethyst.ui.dal.FilterByListParams
import com.vitorpamplona.quartz.experimental.ephemeralStory.EphemeralStoryEvent
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Scans LocalCache for active kind-30923 ephemeral stories scoped by the home top filter
 * (same follow-list flow as posts), last 24h / not expired.
 */
class EphemeralStoryFeedFilter(
    val account: Account,
) : AdditiveFeedFilter<Note>() {
    override fun feedKey(): String = account.userProfile().pubkeyHex + "-" + account.settings.defaultHomeFollowList.value.code

    override fun limit() = 500

    fun followList(): TopFilter = account.settings.defaultHomeFollowList.value

    fun TopFilter.isMuteList() = this is TopFilter.MuteList

    fun TopFilter.isBlockList() = this is TopFilter.PeopleList && this.address == account.blockPeopleList.getBlockListAddress()

    fun TopFilter.wantsToSeeNegativeStuff() = isMuteList() || isBlockList()

    override fun showHiddenKey(): Boolean = followList().wantsToSeeNegativeStuff()

    override fun feed(): List<Note> {
        val params = buildFilterParams(account)
        val notes =
            LocalCache.addressables.filterIntoSet(EphemeralStoryEvent.KIND) { _, it ->
                acceptableEvent(it, params)
            }
        return sort(notes)
    }

    override fun applyFilter(newItems: Set<Note>): Set<Note> = innerApplyFilter(newItems)

    private fun innerApplyFilter(collection: Collection<Note>): Set<Note> {
        val params = buildFilterParams(account)
        return collection.filterTo(HashSet()) { acceptableEvent(it, params) }
    }

    fun acceptableEvent(
        note: Note,
        params: FilterByListParams,
    ): Boolean {
        val noteEvent = note.event
        if (noteEvent !is EphemeralStoryEvent) return false
        if (noteEvent is AddressableEvent && note !is AddressableNote) return false
        if (!noteEvent.stillActive(TimeUtils.now())) return false

        val isMine = noteEvent.pubKey == account.userProfile().pubkeyHex
        // Own stories show immediately after publish (relay list may still be empty
        // until the outbox echo arrives — same as waiting would hide "my ring").
        return (isMine || params.match(noteEvent, note.relays)) &&
            (params.isHiddenList || isMine || account.isAcceptable(note))
    }

    fun buildFilterParams(account: Account): FilterByListParams =
        FilterByListParams.create(
            followLists = account.liveHomeFollowLists.value,
            hiddenUsers = account.hiddenUsers.flow.value,
        )

    override fun sort(items: Set<Note>): List<Note> = items.sortedWith(compareByDescending { it.createdAt() ?: 0L })
}
