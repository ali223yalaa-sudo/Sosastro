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
package com.vitorpamplona.amethyst.ui.note.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.event.observeNoteReplies
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.note.LoadDecryptedContent
import com.vitorpamplona.amethyst.ui.note.NoteAuthorPicture
import com.vitorpamplona.amethyst.ui.note.NoteUsernameDisplay
import com.vitorpamplona.amethyst.ui.note.elements.NormalTimeAgo
import com.vitorpamplona.amethyst.ui.note.types.RenderAudioFromIMeta
import com.vitorpamplona.amethyst.ui.note.types.RenderVoiceTrack
import com.vitorpamplona.amethyst.ui.note.types.isAudioOnlyContent
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.threadview.NoteQuickCommentComposer
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.Size35dp
import com.vitorpamplona.amethyst.ui.theme.placeholderText
import com.vitorpamplona.quartz.nipA0VoiceMessages.BaseVoiceEvent

/**
 * Instagram / Facebook style comments sheet: overlays the current screen (e.g. a
 * reel), lists comments oldest-first with nested replies indented, shows an empty
 * state when there are none, and keeps the composer pinned at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
    note: Note,
    accountViewModel: AccountViewModel,
    nav: INav,
    onDismiss: () -> Unit,
    scrollToCommentId: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val repliesState by observeNoteReplies(note, accountViewModel)
    val topLevelComments =
        remember(repliesState, note.replies.size) {
            note.replies
                .asSequence()
                .filter { comment ->
                    !comment.isDraft() || comment.author?.pubkeyHex == accountViewModel.userProfile().pubkeyHex
                }.sortedBy { it.createdAt() ?: 0L }
                .toList()
        }

    LaunchedEffect(scrollToCommentId, topLevelComments) {
        val id = scrollToCommentId ?: return@LaunchedEffect
        val index =
            topLevelComments.indexOfFirst { comment ->
                comment.idHex == id || comment.replies.any { it.idHex == id }
            }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
        ) {
            Text(
                text = stringRes(R.string.comments_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (topLevelComments.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringRes(R.string.no_comments_yet),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringRes(R.string.no_comments_yet_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.placeholderText,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(
                        items = topLevelComments,
                        key = { it.idHex },
                    ) { comment ->
                        Column {
                            InstagramStyleComment(
                                comment = comment,
                                depth = 0,
                                highlighted = comment.idHex == scrollToCommentId,
                                accountViewModel = accountViewModel,
                                nav = nav,
                            )
                            NestedCommentReplies(
                                parent = comment,
                                highlightedCommentId = scrollToCommentId,
                                accountViewModel = accountViewModel,
                                nav = nav,
                            )
                        }
                    }
                }
            }

            NoteQuickCommentComposer(note, accountViewModel, nav)
        }
    }
}

@Composable
private fun NestedCommentReplies(
    parent: Note,
    highlightedCommentId: String?,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val repliesState by observeNoteReplies(parent, accountViewModel)
    val nested =
        remember(repliesState, parent.replies.size) {
            parent.replies
                .asSequence()
                .filter { comment ->
                    !comment.isDraft() || comment.author?.pubkeyHex == accountViewModel.userProfile().pubkeyHex
                }.sortedBy { it.createdAt() ?: 0L }
                .toList()
        }

    nested.forEach { reply ->
        Spacer(modifier = Modifier.height(10.dp))
        InstagramStyleComment(
            comment = reply,
            depth = 1,
            highlighted = reply.idHex == highlightedCommentId,
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }
}

@Composable
private fun InstagramStyleComment(
    comment: Note,
    depth: Int,
    highlighted: Boolean,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val startPadding = if (depth > 0) 44.dp else 0.dp

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = startPadding)
                .then(
                    if (highlighted) {
                        Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    } else {
                        Modifier
                    },
                ).clickable {
                    nav.nav(Route.Note(comment.idHex))
                },
        verticalAlignment = Alignment.Top,
    ) {
        NoteAuthorPicture(
            baseNote = comment,
            size = Size35dp,
            accountViewModel = accountViewModel,
            nav = nav,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NoteUsernameDisplay(
                    comment,
                    Modifier.weight(1f, fill = false),
                    accountViewModel = accountViewModel,
                )
                NormalTimeAgo(baseNote = comment, modifier = Modifier)
            }
            Spacer(modifier = Modifier.height(2.dp))
            val noteEvent = comment.event
            when {
                noteEvent is BaseVoiceEvent -> {
                    RenderVoiceTrack(comment, accountViewModel, nav)
                }
                noteEvent != null && noteEvent.isAudioOnlyContent() -> {
                    RenderAudioFromIMeta(comment, accountViewModel, nav)
                }
                else -> {
                    LoadDecryptedContent(comment, accountViewModel) { content ->
                        if (content.isNotBlank()) {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
