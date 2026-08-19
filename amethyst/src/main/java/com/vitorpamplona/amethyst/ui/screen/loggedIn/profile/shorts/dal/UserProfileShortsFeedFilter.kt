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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.shorts.dal

import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.model.AddressableNote
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.model.User
import com.vitorpamplona.amethyst.model.filter
import com.vitorpamplona.amethyst.model.filterIntoSet
import com.vitorpamplona.amethyst.ui.dal.AdditiveFeedFilter
import com.vitorpamplona.amethyst.ui.dal.FilterByListParams
import com.vitorpamplona.amethyst.ui.dal.sortedByDefaultFeedOrder
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.isReelPlayableVideo
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.reelPrimaryVideoUrl
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip71Video.VideoEvent
import com.vitorpamplona.quartz.nip71Video.VideoHorizontalEvent
import com.vitorpamplona.quartz.nip71Video.VideoVerticalEvent

class UserProfileShortsFeedFilter(
    val user: User,
    val account: Account,
) : AdditiveFeedFilter<Note>() {
    override fun feedKey(): String = account.userProfile().pubkeyHex + "-" + user.pubkeyHex + "-ProfileShorts"

    override fun feed(): List<Note> {
        val params = buildFilterParams(account)

        val notes =
            LocalCache.notes.filterIntoSet { _, it ->
                acceptableEvent(it, params, user)
            }

        val addressableNotes =
            LocalCache.addressables.filter(
                listOf(VideoVerticalEvent.KIND, VideoHorizontalEvent.KIND),
                user.pubkeyHex,
            ) { _, it ->
                acceptableEvent(it, params, user)
            }

        return sort(addressableNotes + notes).toList()
    }

    override fun applyFilter(newItems: Set<Note>): Set<Note> = innerApplyFilter(newItems)

    private fun innerApplyFilter(collection: Collection<Note>): Set<Note> {
        val params = buildFilterParams(account)
        return collection.filterTo(HashSet()) { acceptableEvent(it, params, user) }
    }

    fun acceptableEvent(
        it: Note,
        params: FilterByListParams,
        user: User,
    ): Boolean {
        val noteEvent = it.event ?: return false
        if (noteEvent.pubKey != user.pubkeyHex) return false
        if (noteEvent is AddressableEvent && it !is AddressableNote) return false
        if (!noteEvent.isReelPlayableVideo()) return false

        return params.match(noteEvent, it.relays) && account.isAcceptable(it)
    }

    fun buildFilterParams(account: Account): FilterByListParams =
        FilterByListParams.create(
            followLists = account.liveShortsFollowLists.value,
            hiddenUsers = account.hiddenUsers.flow.value,
        )

    override fun sort(items: Set<Note>): List<Note> {
        val ordered = items.sortedByDefaultFeedOrder()
        val byUrl = LinkedHashMap<String, Note>()
        for (note in ordered) {
            if (note.event is VideoEvent) {
                val url = note.event?.reelPrimaryVideoUrl() ?: continue
                byUrl.putIfAbsent(url, note)
            }
        }
        for (note in ordered) {
            if (note.event is TextNoteEvent) {
                val url = note.event?.reelPrimaryVideoUrl() ?: continue
                byUrl.putIfAbsent(url, note)
            }
        }
        return byUrl.values.sortedByDefaultFeedOrder()
    }
}
