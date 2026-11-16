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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.BottomStart
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.ui.feeds.FeedState
import com.vitorpamplona.amethyst.commons.ui.layouts.LocalDisappearingScaffoldPadding
import com.vitorpamplona.amethyst.model.AddressableNote
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.event.observeNoteAndMap
import com.vitorpamplona.amethyst.ui.actions.CrossfadeIfEnabled
import com.vitorpamplona.amethyst.ui.components.SensitivityWarning
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.routeFor
import com.vitorpamplona.amethyst.ui.note.DisplayAuthorBanner
import com.vitorpamplona.amethyst.ui.note.Gallery
import com.vitorpamplona.amethyst.ui.note.LikeReaction
import com.vitorpamplona.amethyst.ui.note.LongPressToQuickAction
import com.vitorpamplona.amethyst.ui.note.UserPicture
import com.vitorpamplona.amethyst.ui.note.UsernameDisplay
import com.vitorpamplona.amethyst.ui.note.ZapReaction
import com.vitorpamplona.amethyst.ui.note.elements.MoreOptionsButton
import com.vitorpamplona.amethyst.ui.note.timeAgoNoDot
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.publicChannels.nip53LiveActivities.EndedFlag
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.publicChannels.nip53LiveActivities.LiveFlag
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.publicChannels.nip53LiveActivities.PrivateFlag
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.publicChannels.nip53LiveActivities.ScheduledFlag
import com.vitorpamplona.amethyst.ui.screen.loggedIn.discover.nip53LiveActivities.LoadParticipants
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.dal.NestsFeedFilter
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.dal.NestsFeedFilter.NestBucket
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.datasource.NestRoomLivenessProbeSubscription
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.activity.NestActivity
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.activity.NestBridge
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.screen.UnknownPersonCover
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.DoubleVertSpacer
import com.vitorpamplona.amethyst.ui.theme.QuoteBorder
import com.vitorpamplona.amethyst.ui.theme.RowColSpacing
import com.vitorpamplona.amethyst.ui.theme.Size34dp
import com.vitorpamplona.amethyst.ui.theme.Size35dp
import com.vitorpamplona.amethyst.ui.theme.Size55dp
import com.vitorpamplona.amethyst.ui.theme.StdHorzSpacer
import com.vitorpamplona.amethyst.ui.theme.StdPadding
import com.vitorpamplona.amethyst.ui.theme.grayText
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.tags.StatusTag
import com.vitorpamplona.quartz.nip53LiveActivities.presence.MeetingRoomPresenceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ParticipantTag
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NestsFeedLoaded(
    loaded: FeedState.Loaded,
    listState: LazyListState,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val items by loaded.feed.collectAsStateWithLifecycle()
    val myHex = accountViewModel.userProfile().pubkeyHex

    val layout =
        remember(items.list, myHex) {
            val mine = ArrayList<Note>()
            val live = ArrayList<Note>()
            val scheduled = ArrayList<Note>()
            val ended = ArrayList<Note>()
            items.list.forEach { note ->
                val event = note.event as? MeetingSpaceEvent
                val owned = event?.pubKey == myHex
                if (owned) {
                    mine.add(note)
                } else {
                    when (NestsFeedFilter.bucketOf(event)) {
                        NestBucket.LIVE -> live.add(note)
                        NestBucket.SCHEDULED -> scheduled.add(note)
                        NestBucket.ENDED -> ended.add(note)
                    }
                }
            }
            val myRoom =
                mine.firstOrNull {
                    NestsFeedFilter.bucketOf(it.event as? MeetingSpaceEvent) != NestBucket.ENDED
                } ?: mine.firstOrNull()
            FeedLayout(
                myRoom = myRoom,
                live = live.toImmutableList(),
                scheduled = scheduled.toImmutableList(),
                ended = ended.toImmutableList(),
            )
        }

    val discoverRooms =
        remember(layout) {
            (layout.live + layout.scheduled + layout.ended).toImmutableList()
        }

    LazyColumn(
        contentPadding = rememberNestsGridContentPadding(),
        state = listState,
    ) {
        nestsRoomList(discoverRooms, accountViewModel, nav)
    }
}

@Immutable
private data class FeedLayout(
    val myRoom: Note?,
    val live: ImmutableList<Note>,
    val scheduled: ImmutableList<Note>,
    val ended: ImmutableList<Note>,
)

@Composable
internal fun rememberNestsGridContentPadding(): PaddingValues {
    val scaffold = LocalDisappearingScaffoldPadding.current
    return PaddingValues(bottom = scaffold.calculateBottomPadding() + 10.dp)
}

@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.nestsRoomList(
    rooms: ImmutableList<Note>,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    items(rooms.size, key = { rooms[it].idHex }) { index ->
        NestRoomRow(
            baseNote = rooms[index],
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 5.dp)
                    .animateItem(),
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }
}

/**
 * Compact audio-rooms list row. The old grid card was a full-width 16:9
 * cover, which filled the screen and forced a heavy cover decode per item.
 * This is a single 84dp row with a small square thumbnail, so a dozen rooms
 * fit on screen and the list scrolls cheaply. Same tap routing as before:
 * [EndedFlag]-style cards go through the read-only lobby, live rooms launch
 * [NestActivity] directly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NestRoomRow(
    baseNote: Note,
    modifier: Modifier,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val meetingEvent = baseNote.event as? MeetingSpaceEvent ?: return
    val addressableNote = baseNote as? AddressableNote ?: return

    val context = LocalContext.current
    val status = meetingEvent.checkStatus(meetingEvent.status())
    val isUiClosed = status == null || status == StatusTag.STATUS.ENDED
    val onClick =
        remember(meetingEvent, isUiClosed) {
            {
                openRoom(meetingEvent, baseNote, isUiClosed, accountViewModel, nav, context)
            }
        }

    val name = meetingEvent.room()?.ifBlank { null } ?: meetingEvent.dTag()
    val cover = meetingEvent.image()?.ifBlank { null }
    val subtitle = meetingEvent.summary()?.ifBlank { null }
    val listeners by observeRoomOccupancyCount(addressableNote, accountViewModel)
    val live = status == StatusTag.STATUS.LIVE
    val shape = RoundedCornerShape(14.dp)

    LongPressToQuickAction(baseNote, accountViewModel, nav) { showQuickAction ->
        Card(
            modifier =
                modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = showQuickAction,
                ),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = NestLobbyCard),
            border = BorderStroke(1.dp, NestGridBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(84.dp).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(11.dp)),
                ) {
                    if (cover != null) {
                        AsyncImage(
                            model = cover,
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        UnknownPersonCover(Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val creator = baseNote.author
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            color = Color(0xFF9A9A9A),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    } else if (creator != null) {
                        UsernameDisplay(
                            baseUser = creator,
                            fontWeight = FontWeight.Normal,
                            textColor = MaterialTheme.colorScheme.grayText,
                            accountViewModel = accountViewModel,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (live) NestSignalTeal else Color(0xFF6A6A6A)),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = listeners.toString(),
                            color = Color(0xFF9A9A9A),
                            fontSize = 12.sp,
                        )
                    }
                }
                Text(
                    text = "›",
                    color = Color(0xFF6A6A6A),
                    fontSize = 22.sp,
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                )
            }
        }
    }
}

/**
 * Resolve the destination for tapping a room card and navigate.
 * Stale-promoted LIVE rooms and ENDED rooms route to the lobby
 * (read-only) so re-entry doesn't kick off the audio pipeline or
 * trigger host-side kind-30312 refreshes on a dead room. Live and
 * private rooms launch [NestActivity] directly. Rooms missing
 * service/endpoint tags fall back to the standard note route so
 * the user at least sees what we know about the event.
 */
internal fun openRoom(
    meetingEvent: MeetingSpaceEvent,
    baseNote: Note,
    isUiClosed: Boolean,
    accountViewModel: AccountViewModel,
    nav: INav,
    context: android.content.Context,
) {
    val service = meetingEvent.service()
    val endpoint = meetingEvent.endpoint()
    val dTag = meetingEvent.address().dTag
    if (!service.isNullOrBlank() && !endpoint.isNullOrBlank() && dTag.isNotBlank()) {
        // Always open the live room UI directly — no lobby / intermediate screen.
        NestBridge.set(accountViewModel)
        NestActivity.launch(
            context = context,
            addressValue = meetingEvent.address().toValue(),
        )
    } else {
        nav.nav { routeFor(baseNote, accountViewModel.account) }
    }
}

@Composable
fun ObserveAndRenderSpace(
    baseNote: AddressableNote,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val card by observeNoteAndMap(baseNote, accountViewModel) {
        when (val noteEvent = it.event) {
            is MeetingSpaceEvent -> {
                NestCard(
                    id = noteEvent.address(),
                    name = noteEvent.dTag(),
                    cover = noteEvent.image()?.ifBlank { null },
                    media = null,
                    subject = noteEvent.room()?.ifBlank { null },
                    content = noteEvent.summary(),
                    participants = noteEvent.participants().toImmutableList(),
                    status = noteEvent.checkStatus(noteEvent.status()),
                    starts = noteEvent.starts(),
                )
            }

            else -> {
                null
            }
        }
    }

    card?.let {
        RenderLiveSpacesThumb(
            it,
            baseNote,
            accountViewModel,
            nav,
        )
    }
}

@Immutable
data class NestCard(
    val id: Address?,
    val name: String,
    val cover: String?,
    val media: String?,
    val subject: String?,
    val content: String?,
    val participants: ImmutableList<ParticipantTag>,
    val status: StatusTag.STATUS?,
    val starts: Long?,
)

/**
 * Window inside which a kind-10312 presence event still counts the
 * room as "live": three consecutive 60 s heartbeat periods. Speakers
 * publish presence on join and re-publish every ~60 s while
 * broadcasting, so anything fresher than this means at least one
 * speaker is still in the room. Older means the host crashed
 * without flipping status to CLOSED, and we should demote the badge
 * to "ended" even though the kind-30312 event still says OPEN.
 */
private const val PRESENCE_FRESHNESS_WINDOW_SECONDS = 180L

/**
 * Liveness-gated badge for an OPEN room. Optimistically renders
 * [LiveFlag] on first paint (we have no presence data yet) and
 * keeps it once a fresh kind-10312 presence lands; flips to
 * [EndedFlag] once we observe that the latest cached presence is
 * older than [PRESENCE_FRESHNESS_WINDOW_SECONDS]. Falls back to
 * [LiveFlag] when the room has no addressable id (defensive — every
 * 30312 event has one in practice).
 */
@Composable
private fun RenderLiveOrEndedFromPresence(
    note: AddressableNote?,
    accountViewModel: AccountViewModel,
) {
    if (note == null) {
        LiveFlag()
        return
    }
    val latestPresence by observeRoomLatestPresence(note, accountViewModel)
    val fresh = latestPresence == null || latestPresence!! > TimeUtils.now() - PRESENCE_FRESHNESS_WINDOW_SECONDS
    if (fresh) LiveFlag() else EndedFlag()
}

/**
 * Most recent kind-10312 presence `createdAt` cached for [address],
 * or null until one arrives. Uses the dedicated
 * [NestRoomLivenessProbeSubscription] — `kinds=[10312], #a=[roomA],
 * limit=1` per outbox relay — so a feed of N rooms doesn't pay
 * each popular room's chat-history pull just to render a LIVE /
 * ENDED badge. The assembler reads version notes off the room's
 * [LocalCache] channel, which `LocalCache.consume(MeetingRoomPresenceEvent, …)`
 * attaches by `#a=room`. The full chat / reaction / admin REQs
 * stay gated behind the join flow (see [NestRoomFilterAssemblerSubscription]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun observeRoomLatestPresence(
    note: AddressableNote,
    accountViewModel: AccountViewModel,
): State<Long?> {
    NestRoomLivenessProbeSubscription(note, accountViewModel)

    val channel = remember(note.idHex) { LocalCache.getOrCreateLiveChannel(note.address) }
    val flow =
        remember(channel) {
            channel
                .flow()
                .notes.stateFlow
                .mapLatest { _ ->
                    var max: Long? = null
                    channel.presenceNotes.forEach { _, note ->
                        val event = note.event
                        if (event is MeetingRoomPresenceEvent && event.left() != true) {
                            val createdAt = event.createdAt
                            if (max == null || createdAt > max!!) max = createdAt
                        }
                    }
                    max
                }.distinctUntilChanged()
                .flowOn(Dispatchers.IO)
        }
    return flow.collectAsStateWithLifecycle(null)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun observeRoomOccupancyCount(
    note: AddressableNote,
    accountViewModel: AccountViewModel,
): State<Int> {
    NestRoomLivenessProbeSubscription(note, accountViewModel)

    val channel = remember(note.idHex) { LocalCache.getOrCreateLiveChannel(note.address) }
    val flow =
        remember(channel) {
            channel
                .flow()
                .notes.stateFlow
                .mapLatest { _ ->
                    val cutoff = TimeUtils.now() - PRESENCE_FRESHNESS_WINDOW_SECONDS
                    var count = 0
                    channel.presenceNotes.forEach { _, presenceNote ->
                        val event = presenceNote.event
                        if (event is MeetingRoomPresenceEvent &&
                            event.left() != true &&
                            event.createdAt > cutoff
                        ) {
                            count++
                        }
                    }
                    count
                }.distinctUntilChanged()
                .flowOn(Dispatchers.IO)
        }
    return flow.collectAsStateWithLifecycle(0)
}

@Composable
fun RenderLiveSpacesThumb(
    card: NestCard,
    baseNote: AddressableNote,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = TopEnd,
            modifier =
                Modifier
                    .aspectRatio(ratio = 16f / 9f)
                    .fillMaxWidth(),
        ) {
            card.cover?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(QuoteBorder),
                )
            } ?: run { DisplayAuthorBanner(baseNote, accountViewModel) }

            Box(Modifier.padding(10.dp)) {
                CrossfadeIfEnabled(targetState = card.status, accountViewModel = accountViewModel) {
                    when (it) {
                        StatusTag.STATUS.LIVE -> {
                            RenderLiveOrEndedFromPresence(baseNote, accountViewModel)
                        }

                        StatusTag.STATUS.ENDED -> {
                            EndedFlag()
                        }

                        StatusTag.STATUS.PLANNED -> {
                            ScheduledFlag(card.starts)
                        }

                        StatusTag.STATUS.PRIVATE -> {
                            PrivateFlag()
                        }

                        else -> {
                            EndedFlag()
                        }
                    }
                }
            }

            LoadParticipants(card.participants, baseNote, accountViewModel) { participantUsers ->
                Box(
                    Modifier
                        .padding(10.dp)
                        .align(BottomStart),
                ) {
                    if (participantUsers.isNotEmpty()) {
                        Gallery(participantUsers, Modifier, accountViewModel, nav)
                    }
                }
            }
        }

        Spacer(modifier = DoubleVertSpacer)

        SpaceHostAndReactions(
            card.subject,
            baseNote,
            accountViewModel,
            nav,
        )
    }
}

@Composable
fun SpaceHostAndReactions(
    name: String?,
    baseNote: AddressableNote,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val creator = baseNote.author
        if (creator == null) {
            Text(
                text = stringRes(R.string.wallet_loading),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            UserPicture(
                user = creator,
                size = Size34dp,
                accountViewModel = accountViewModel,
                nav = nav,
            )

            Column(
                modifier =
                    Modifier
                        .padding(start = 10.dp)
                        .height(35.dp)
                        .weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!name.isNullOrBlank()) {
                        Text(
                            text = name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        UsernameDisplay(creator, accountViewModel = accountViewModel)
                    }
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .height(Size35dp)
                    .padding(start = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = RowColSpacing,
        ) {
            LikeReaction(
                baseNote = baseNote,
                grayTint = MaterialTheme.colorScheme.onSurface,
                accountViewModel = accountViewModel,
                nav = nav,
            )
            Spacer(modifier = StdHorzSpacer)
            ZapReaction(
                baseNote = baseNote,
                grayTint = MaterialTheme.colorScheme.onSurface,
                accountViewModel = accountViewModel,
                nav = nav,
            )
            Spacer(modifier = StdHorzSpacer)
            MoreOptionsButton(
                baseNote = baseNote,
                accountViewModel = accountViewModel,
                nav = nav,
            )
        }
    }
}
