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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.ui.actions.MentionPreservingInputTransformation
import com.vitorpamplona.amethyst.ui.actions.StrippingFailureDialog
import com.vitorpamplona.amethyst.ui.actions.UrlUserTagOutputTransformation
import com.vitorpamplona.amethyst.ui.actions.uploads.SelectFromGallery
import com.vitorpamplona.amethyst.ui.components.ThinPaddingTextField
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.note.creators.emojiSuggestions.ShowEmojiSuggestionList
import com.vitorpamplona.amethyst.ui.note.creators.userSuggestions.ShowUserSuggestionList
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.utils.DisplayReplyingToNote
import com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.utils.ThinSendButton
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.EditFieldBorder
import com.vitorpamplona.amethyst.ui.theme.EditFieldModifier
import com.vitorpamplona.amethyst.ui.theme.EditFieldTrailingIconModifier
import com.vitorpamplona.amethyst.ui.theme.SuggestionListDefaultHeightChat
import com.vitorpamplona.amethyst.ui.theme.placeholderText
import kotlinx.coroutines.FlowPreview

/**
 * Mirror of `EditFieldRow` for the nest-room composer. Uses
 * [NestNewMessageViewModel] which is scoped to a single
 * [com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent]
 * and only emits kind-1311 chat messages.
 *
 * Renders, top-to-bottom: stripping-failure dialog, reply preview,
 * file-upload dialog, @-mention picker, emoji suggestions, and the
 * text field with gallery picker + send button.
 *
 * Differences from the channel `EditFieldRow`:
 *  - No `BackHandler`: the nest chat panel lives inside an Activity
 *    that owns its own back behaviour (leave room / enter PiP). The
 *    composer's draft auto-save runs on every text change via
 *    [NestNewMessageViewModel.draftTag], so there is nothing to flush
 *    on back press.
 */
@OptIn(FlowPreview::class)
@Composable
fun NestEditFieldRow(
    nestScreenModel: NestNewMessageViewModel,
    accountViewModel: AccountViewModel,
    onSendNewMessage: suspend () -> Unit,
    nav: INav,
) {
    StrippingFailureDialog(nestScreenModel.strippingFailureConfirmation)

    nestScreenModel.replyTo.value?.let {
        DisplayReplyingToNote(it, accountViewModel, nav) {
            nestScreenModel.clearReply()
        }
    }

    nestScreenModel.uploadState?.let { uploading ->
        uploading.multiOrchestrator?.let {
            NestFileUploadDialog(
                nestScreenModel = nestScreenModel,
                state = uploading,
                onUpload = onSendNewMessage,
                onCancel = uploading::reset,
                accountViewModel = accountViewModel,
                nav = nav,
            )
        }
    }

    // This composer is the terminal focus sink in the room: it never
    // requests focus on its own, but once the user taps it the keyboard
    // owns the back gesture. `NestActivityContent` gates its
    // minimize-to-PIP back on the IME inset, but that inset is only
    // reported in edge-to-edge windows (`NestActivity` does not call
    // `enableEdgeToEdge`), so on its own it can leave back unhandled
    // and strand the keyboard. This handler keys off the text field's
    // *focus* instead — independent of the inset — so back reliably
    // dismisses the keyboard (and only the keyboard) before the room's
    // back-to-PIP handler runs.
    val focusManager: FocusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val composerInteraction = remember { MutableInteractionSource() }
    val isComposerFocused by composerInteraction.collectIsFocusedAsState()
    BackHandler(enabled = isComposerFocused) {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    Column(
        modifier = EditFieldModifier,
    ) {
        nestScreenModel.userSuggestions?.let {
            ShowUserSuggestionList(
                it,
                nestScreenModel::autocompleteWithUser,
                accountViewModel,
                SuggestionListDefaultHeightChat,
            )
        }

        nestScreenModel.emojiSuggestions?.let {
            ShowEmojiSuggestionList(
                it,
                nestScreenModel::autocompleteWithEmoji,
                nestScreenModel::autocompleteWithEmojiUrl,
                SuggestionListDefaultHeightChat,
            )
        }

        ThinPaddingTextField(
            state = nestScreenModel.message,
            onTextChanged = { nestScreenModel.onMessageChanged() },
            inputTransformation = MentionPreservingInputTransformation,
            keyboardOptions =
                KeyboardOptions.Default.copy(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            shape = EditFieldBorder,
            modifier = Modifier.fillMaxWidth(),
            interactionSource = composerInteraction,
            placeholder = {
                Text(
                    text = stringRes(R.string.nest_say_something),
                    color = MaterialTheme.colorScheme.placeholderText,
                    fontSize = 16.sp,
                )
            },
            textStyle =
                LocalTextStyle.current.copy(
                    textDirection = TextDirection.Content,
                    fontSize = 16.sp,
                ),
            trailingIcon = {
                ThinSendButton(
                    isActive =
                        nestScreenModel.message.text.isNotBlank() && !nestScreenModel.isUploadingImage,
                    modifier = EditFieldTrailingIconModifier,
                ) {
                    nestScreenModel.sendPost(onSendNewMessage)
                }
            },
            leadingIcon = {
                SelectFromGallery(
                    isUploading = nestScreenModel.isUploadingImage,
                    tint = MaterialTheme.colorScheme.placeholderText,
                    modifier = Modifier.height(32.dp).padding(start = 2.dp),
                    onImageChosen = nestScreenModel::pickedMedia,
                )
            },
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            outputTransformation = UrlUserTagOutputTransformation(MaterialTheme.colorScheme.primary),
        )
    }
}
