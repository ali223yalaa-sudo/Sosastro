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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.viewmodels.NestViewModel
import com.vitorpamplona.amethyst.commons.viewmodels.RoomNotice
import com.vitorpamplona.amethyst.model.AddressableNote
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.ui.navigation.navs.BouncingIntentNav
import com.vitorpamplona.amethyst.ui.note.ClickableUserPicture
import com.vitorpamplona.amethyst.ui.note.UsernameDisplay
import com.vitorpamplona.amethyst.ui.note.WatchBlockAndReport
import com.vitorpamplona.amethyst.ui.note.WatchNoteEvent
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.feed.ChatroomMessageCompose
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.feed.types.RenderRegularTextNote
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel

@Composable
internal fun ColumnScope.NestChatPanel(
    event: MeetingSpaceEvent,
    roomNote: AddressableNote,
    viewModel: NestViewModel,
    accountViewModel: AccountViewModel,
    modifier: Modifier = Modifier,
    nestScreenModel: NestNewMessageViewModel? = null,
    includeComposer: Boolean = true,
    compact: Boolean = false,
) {
    val messages by viewModel.chat.collectAsState()
    val notices by viewModel.roomNotices.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = remember(context, scope) { BouncingIntentNav(context, scope) }

    val routeForLastRead = remember(event) { "NestChat/${event.address().toValue()}" }

    val ownedModel =
        nestScreenModel ?: composeViewModel<NestNewMessageViewModel>(key = "Nest/${event.address().toValue()}")
    ownedModel.init(accountViewModel)
    ownedModel.load(roomNote)

    val listState = rememberLazyListState()

    LaunchedEffect(messages.firstOrNull()?.idHex) {
        if (listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
        ) {
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringRes(R.string.nest_chat_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                NestChatMessageList(
                    messages = messages,
                    listState = listState,
                    routeForLastRead = routeForLastRead,
                    accountViewModel = accountViewModel,
                    nav = nav,
                    compact = compact,
                    onWantsToReply = ownedModel::reply,
                    onWantsToEditDraft = ownedModel::editFromDraft,
                )
            }
        }

        if (notices.isNotEmpty()) {
            RoomNoticeTicker(
                notices = notices,
                accountViewModel = accountViewModel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }

        if (includeComposer) {
            Spacer(Modifier.height(8.dp))
            NestEditFieldRow(
                nestScreenModel = ownedModel,
                accountViewModel = accountViewModel,
                onSendNewMessage = {
                    scope.launch {
                        delay(100)
                        listState.animateScrollToItem(0)
                    }
                },
                nav = nav,
            )
        }
    }
}

@Composable
private fun NestChatMessageList(
    messages: List<Note>,
    routeForLastRead: String,
    listState: LazyListState,
    accountViewModel: AccountViewModel,
    nav: BouncingIntentNav,
    compact: Boolean,
    onWantsToReply: (Note) -> Unit,
    onWantsToEditDraft: (Note) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
    ) {
        items(
            items = messages,
            key = { it.idHex },
        ) { note ->
            if (compact) {
                NestCompactChatLine(
                    baseNote = note,
                    accountViewModel = accountViewModel,
                    nav = nav,
                )
            } else {
                ChatroomMessageCompose(
                    baseNote = note,
                    routeForLastRead = routeForLastRead,
                    accountViewModel = accountViewModel,
                    nav = nav,
                    onWantsToReply = onWantsToReply,
                    onWantsToEditDraft = onWantsToEditDraft,
                )
            }
        }
    }
}

/**
 * Compact, non-intrusive ticker of the most recent enter/exit notices.
 * Renders the last couple of transitions as centered system lines so
 * every peer sees "X entered" / "X exited" the instant presence flips,
 * without a separate wire channel or a modal. The full ledger lives in
 * [NestViewModel.roomNotices]; only the tail is shown to avoid clutter.
 */
@Composable
private fun RoomNoticeTicker(
    notices: List<RoomNotice>,
    accountViewModel: AccountViewModel,
    modifier: Modifier = Modifier,
) {
    val recent = remember(notices) { notices.takeLast(2) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        recent.forEach { notice ->
            val user = remember(notice.pubkey) { LocalCache.getOrCreateUser(notice.pubkey) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClickableUserPicture(
                    baseUser = user,
                    size = 16.dp,
                    accountViewModel = accountViewModel,
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                UsernameDisplay(
                    baseUser = user,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    textColor =
                        if (notice.kind == RoomNotice.Kind.ENTERED) {
                            Color(0xFF2EE0C5)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    accountViewModel = accountViewModel,
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    text =
                        stringRes(
                            if (notice.kind == RoomNotice.Kind.ENTERED) {
                                R.string.nest_notice_entered
                            } else {
                                R.string.nest_notice_exited
                            },
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (notice.kind == RoomNotice.Kind.ENTERED) {
                            Color(0xFF2EE0C5)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun NestCompactChatLine(
    baseNote: Note,
    accountViewModel: AccountViewModel,
    nav: BouncingIntentNav,
) {
    WatchNoteEvent(
        baseNote = baseNote,
        onNoteEventFound = {
            WatchBlockAndReport(
                note = baseNote,
                showHiddenWarning = false,
                modifier = Modifier.fillMaxWidth(),
                accountViewModel = accountViewModel,
                nav = nav,
            ) { canPreview ->
                val bg = remember { mutableStateOf(Color.Transparent) }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.widthIn(max = 260.dp).padding(end = 8.dp)) {
                        RenderRegularTextNote(
                            note = baseNote,
                            canPreview = canPreview,
                            innerQuote = false,
                            bgColor = bg,
                            accountViewModel = accountViewModel,
                            nav = nav,
                        )
                    }
                    baseNote.author?.let { author ->
                        ClickableUserPicture(
                            baseUser = author,
                            size = 22.dp,
                            accountViewModel = accountViewModel,
                            onClick = {
                                nav.nav(
                                    com.vitorpamplona.amethyst.ui.navigation.routes
                                        .routeFor(it),
                                )
                            },
                        )
                    }
                }
            }
        },
        onBlank = {},
        accountViewModel = accountViewModel,
    )
}
