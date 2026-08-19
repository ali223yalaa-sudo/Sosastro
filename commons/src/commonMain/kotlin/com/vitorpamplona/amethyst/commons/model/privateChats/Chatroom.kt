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
package com.vitorpamplona.amethyst.commons.model.privateChats

import androidx.compose.runtime.Stable
import com.vitorpamplona.amethyst.commons.model.Channel.Companion.DefaultFeedOrder
import com.vitorpamplona.amethyst.commons.model.ListChange
import com.vitorpamplona.amethyst.commons.model.Note
import com.vitorpamplona.amethyst.commons.model.NotesGatherer
import com.vitorpamplona.amethyst.commons.model.User
import com.vitorpamplona.amethyst.commons.util.KmpLock
import com.vitorpamplona.amethyst.commons.util.WeakReference
import com.vitorpamplona.amethyst.commons.util.withLock
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.paging.RelayLoadingCursors
import com.vitorpamplona.quartz.nip04Dm.messages.PrivateDmEvent
import com.vitorpamplona.quartz.nip14Subject.subject
import com.vitorpamplona.quartz.nip17Dm.base.BaseDMGroupEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

@Stable
class Chatroom : NotesGatherer {
    var activeSenders: Set<User> = setOf()
    var messages: Set<Note> = setOf()
    var subject = MutableStateFlow<String?>(null)
    var subjectCreatedAt: Long? = null
    var ownerSentMessage: Boolean = false
    var newestMessage: Note? = null

    /** Latest encrypted delivery-ack watermark per author (device was online and unwrapped). */
    val deliveredUntilByAuthor = MutableStateFlow<Map<HexKey, Long>>(emptyMap())

    /** Latest encrypted read-receipt watermark per author (peer pubkey → seen-until createdAt). */
    val readUntilByAuthor = MutableStateFlow<Map<HexKey, Long>>(emptyMap())

    /** Shared 1:1 bubble/background theme id, last-write-wins by rumor createdAt. */
    val sharedThemeId = MutableStateFlow(DEFAULT_CHAT_THEME_ID)
    val composerLike = MutableStateFlow<String?>(null)

    private var themeCreatedAt: Long = 0L
    private var lastSentReadUntil: Long = 0L
    private var pendingDeliveredUntil: Long = 0L
    private var lastSentDeliveredUntil: Long = 0L

    // Per-conversation NIP-04 history paging cursors, held here so reopening this room keeps its
    // progress and the cursors share the lifetime of the cached messages. The conversation history
    // loader binds its (single-active) orchestrator to this. Lazy — most rooms in the rooms list are
    // never opened for history paging, so they never allocate it.
    val nip04History by lazy { RelayLoadingCursors() }

    // Per-instance lock shared by previously @Synchronized methods.
    private val syncLock = KmpLock()

    private var changesFlow: WeakReference<MutableSharedFlow<ListChange<Note>>>? = null

    fun changesFlow(): MutableSharedFlow<ListChange<Note>> {
        val current = changesFlow?.get()
        if (current != null) return current
        val new = MutableSharedFlow<ListChange<Note>>(0, 100, BufferOverflow.DROP_OLDEST)
        changesFlow = WeakReference(new)
        return new
    }

    override fun removeNote(note: Note) {
        removeMessageSync(note)
    }

    fun addMessageSync(msg: Note): Boolean =
        syncLock.withLock {
            if (ingestControlLocked(msg)) {
                return@withLock false
            }
            if (msg !in messages) {
                messages = messages + msg
                msg.addGatherer(this)

                msg.author?.let { author ->
                    if (author !in activeSenders) {
                        activeSenders = activeSenders + author
                    }
                }

                val createdAt = msg.createdAt() ?: 0L
                if (createdAt > (newestMessage?.createdAt() ?: 0L)) {
                    newestMessage = msg
                }

                val newSubject = msg.event?.subject()

                if (newSubject != null && (msg.createdAt() ?: 0L) > (subjectCreatedAt ?: 0)) {
                    subject.tryEmit(newSubject)
                    subjectCreatedAt = msg.createdAt()
                }

                changesFlow?.get()?.tryEmit(ListChange.Addition(msg))

                return@withLock true
            }
            return@withLock false
        }

    fun removeMessageSync(msg: Note): Boolean =
        syncLock.withLock {
            if (msg in messages) {
                messages = messages - msg
                msg.removeGatherer(this)

                if (msg == newestMessage) {
                    newestMessage = messages.maxByOrNull { it.createdAt() ?: 0L }
                }

                if (msg.event?.subject() == subject.value) {
                    messages
                        .maxByOrNull {
                            val noteEvent = it.event
                            if (noteEvent?.subject() != null) {
                                noteEvent.createdAt
                            } else {
                                0
                            }
                        }?.let {
                            subject.tryEmit(it.event?.subject())
                            subjectCreatedAt = it.createdAt()
                        }
                }

                changesFlow?.get()?.tryEmit(ListChange.Deletion(msg))

                return@withLock true
            }
            return@withLock false
        }

    fun senderIntersects(keySet: Set<HexKey>): Boolean = activeSenders.any { it.pubkeyHex in keySet }

    /**
     * Pulls leftover control rumors out of [messages] (from before they were hidden)
     * so room previews and occupancy never treat a receipt/theme as the last chat line.
     */
    fun stripAndIngestControls() {
        syncLock.withLock {
            val leftover = messages.filter { ChatControlMessage.isControl(it.event?.content) }
            if (leftover.isEmpty()) return@withLock
            leftover.forEach { note ->
                ingestControlLocked(note)
                messages = messages - note
                note.removeGatherer(this)
            }
            newestMessage = messages.maxByOrNull { it.createdAt() ?: 0L }
            changesFlow?.get()?.tryEmit(ListChange.SetDeletion(leftover.toSet()))
        }
    }

    fun markReadReceiptSent(until: Long): Boolean =
        syncLock.withLock {
            if (until <= lastSentReadUntil) return@withLock false
            lastSentReadUntil = until
            true
        }

    fun raisePendingDeliveredAck(until: Long) {
        syncLock.withLock {
            if (until > pendingDeliveredUntil) pendingDeliveredUntil = until
        }
    }

    fun takePendingDeliveredAck(): Long? =
        syncLock.withLock {
            if (pendingDeliveredUntil <= lastSentDeliveredUntil) return@withLock null
            lastSentDeliveredUntil = pendingDeliveredUntil
            lastSentDeliveredUntil
        }

    fun applySharedTheme(
        themeId: String,
        createdAt: Long,
    ) {
        syncLock.withLock {
            applyThemeLocked(themeId, createdAt)
        }
    }

    private fun ingestControlLocked(msg: Note): Boolean {
        val content = msg.event?.content ?: return false
        if (!ChatControlMessage.isControl(content)) return false
        val author = msg.author?.pubkeyHex
        val createdAt = msg.createdAt() ?: 0L
        ChatControlMessage.parseDeliveredUntil(content)?.let { until ->
            bumpWatermarkLocked(deliveredUntilByAuthor, author, until)
        }
        ChatControlMessage.parseReadUntil(content)?.let { until ->
            bumpWatermarkLocked(readUntilByAuthor, author, until)
            bumpWatermarkLocked(deliveredUntilByAuthor, author, until)
        }
        ChatControlMessage.parseThemeId(content)?.let { id ->
            applyThemeLocked(id, createdAt)
        }
        return true
    }

    private fun bumpWatermarkLocked(
        flow: MutableStateFlow<Map<HexKey, Long>>,
        author: HexKey?,
        until: Long,
    ) {
        if (author == null) return
        val current = flow.value
        val prev = current[author] ?: 0L
        if (until > prev) {
            flow.value = current + (author to until)
        }
    }

    private fun applyThemeLocked(
        themeId: String,
        createdAt: Long,
    ) {
        if (createdAt < themeCreatedAt) return
        themeCreatedAt = createdAt
        if (sharedThemeId.value != themeId) {
            sharedThemeId.value = themeId
        }
    }

    companion object {
        const val DEFAULT_CHAT_THEME_ID = "classic"
    }

    fun pruneMessagesToTheLatestOnly(): Set<Note> {
        val sorted = messages.sortedWith(DefaultFeedOrder)

        val toKeep =
            if ((sorted.firstOrNull()?.createdAt() ?: 0L) > DmHistoryTuning.recentBoundary()) {
                // Recent conversation, keep its newest N
                sorted.take(DmHistoryTuning.recentKeepCount).toSet()
            } else {
                // Old conversation, keep the last one.
                sorted.take(1).toSet()
            } + sorted.filter { it.flowSet?.isInUse() ?: false } + sorted.filter { it.event !is PrivateDmEvent && it.event !is BaseDMGroupEvent }
        // Both DM protocols are pruned by the recency rule above: NIP-04 (PrivateDmEvent) and NIP-17
        // (BaseDMGroupEvent rumors — ChatMessageEvent / file headers). Anything else that ever lands in a
        // room is kept. The caller realigns the per-relay download window for the dropped messages so
        // they can be paged again later (see LocalCache.pruneOldMessages + RelayLoadingCursors.rewindTo).

        val toRemove = messages.minus(toKeep)
        messages = toKeep

        changesFlow?.get()?.tryEmit(ListChange.SetDeletion<Note>(toRemove))

        return toRemove
    }
}
