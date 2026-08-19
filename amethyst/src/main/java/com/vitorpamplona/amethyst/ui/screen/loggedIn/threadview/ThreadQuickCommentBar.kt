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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.threadview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.ui.actions.uploads.MAX_VOICE_RECORD_SECONDS
import com.vitorpamplona.amethyst.ui.actions.uploads.RecordAudioBox
import com.vitorpamplona.amethyst.ui.components.LoadNote
import com.vitorpamplona.amethyst.ui.components.ThinPaddingTextField
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.note.VoiceReplyIcon
import com.vitorpamplona.amethyst.ui.note.nip22Comments.CommentPostViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.utils.ThinSendButton
import com.vitorpamplona.amethyst.ui.screen.loggedIn.home.ShortNotePostViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.DividerThickness
import com.vitorpamplona.amethyst.ui.theme.EditFieldBorder
import com.vitorpamplona.amethyst.ui.theme.EditFieldTrailingIconModifier
import com.vitorpamplona.amethyst.ui.theme.Size20Modifier
import com.vitorpamplona.amethyst.ui.theme.Size5dp
import com.vitorpamplona.amethyst.ui.theme.placeholderText
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nipA0VoiceMessages.BaseVoiceEvent
import kotlinx.serialization.json.Json

@Composable
fun ThreadQuickCommentBar(
    noteId: String,
    accountViewModel: AccountViewModel,
    nav: INav? = null,
) {
    if (!accountViewModel.isWriteable()) return

    LoadNote(noteId, accountViewModel) { note ->
        if (note != null) {
            NoteQuickCommentComposer(note, accountViewModel, nav)
        }
    }
}

@Composable
fun NoteQuickCommentComposer(
    note: Note,
    accountViewModel: AccountViewModel,
    nav: INav? = null,
) {
    if (!accountViewModel.isWriteable()) return

    when (note.event) {
        is TextNoteEvent, is BaseVoiceEvent -> {
            TextNoteQuickCommentBar(note, accountViewModel, nav)
        }
        else -> {
            Nip22QuickCommentBar(note, accountViewModel, nav)
        }
    }
}

@Composable
private fun TextNoteQuickCommentBar(
    note: Note,
    accountViewModel: AccountViewModel,
    nav: INav?,
) {
    val postViewModel: ShortNotePostViewModel = viewModel(key = note.idHex + "QuickTextComment")
    postViewModel.init(accountViewModel)

    LaunchedEffect(note.idHex) {
        postViewModel.load(note, null, null, null, null)
    }

    QuickCommentField(
        note = note,
        messageState = postViewModel.message,
        canPost = postViewModel::canPost,
        onMessageChanged = postViewModel::onMessageChanged,
        nav = nav,
        onSend = {
            accountViewModel.launchSigner {
                postViewModel.sendPostSync()
            }
        },
    )
}

@Composable
private fun Nip22QuickCommentBar(
    note: Note,
    accountViewModel: AccountViewModel,
    nav: INav?,
) {
    val postViewModel: CommentPostViewModel = viewModel(key = note.idHex + "QuickNip22Comment")
    postViewModel.init(accountViewModel)

    LaunchedEffect(note.idHex) {
        postViewModel.reply(note)
    }

    QuickCommentField(
        note = note,
        messageState = postViewModel.message,
        canPost = postViewModel::canPost,
        onMessageChanged = postViewModel::onMessageChanged,
        nav = nav,
        onSend = {
            accountViewModel.launchSigner {
                postViewModel.sendPostSync()
            }
        },
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun QuickCommentField(
    note: Note,
    messageState: TextFieldState,
    canPost: () -> Boolean,
    onMessageChanged: () -> Unit,
    nav: INav?,
    onSend: () -> Unit,
) {
    var sendEnabled by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = DividerThickness)
        ThinPaddingTextField(
            state = messageState,
            onTextChanged = {
                onMessageChanged()
                sendEnabled = canPost()
            },
            keyboardOptions =
                KeyboardOptions.Default.copy(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            shape = EditFieldBorder,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = Size5dp),
            placeholder = {
                Text(
                    text = stringRes(R.string.add_a_comment),
                    color = MaterialTheme.colorScheme.placeholderText,
                )
            },
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (nav != null) {
                        // Hold/tap mic → VoiceReply route (reply scoped to this note, not a new post).
                        RecordAudioBox(
                            modifier = Size20Modifier,
                            onRecordTaken = { audio ->
                                nav.nav {
                                    Route.VoiceReply(
                                        replyToNoteId = note.idHex,
                                        recordingFilePath = audio.file.absolutePath,
                                        mimeType = audio.mimeType,
                                        duration = audio.duration,
                                        amplitudes = Json.encodeToString(audio.amplitudes),
                                    )
                                }
                            },
                            maxDurationSeconds = MAX_VOICE_RECORD_SECONDS,
                        ) { isRecording, _, _ ->
                            VoiceReplyIcon(
                                Size20Modifier,
                                if (isRecording) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.placeholderText
                                },
                            )
                        }
                    }
                    ThinSendButton(
                        isActive = sendEnabled,
                        modifier = EditFieldTrailingIconModifier,
                        onClick = {
                            onSend()
                            sendEnabled = false
                        },
                    )
                }
            },
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
        )
    }
}
