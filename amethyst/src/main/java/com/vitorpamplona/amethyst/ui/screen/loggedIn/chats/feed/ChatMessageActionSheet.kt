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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.chats.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbol
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.tts.TextToSpeechHelper
import com.vitorpamplona.amethyst.ui.actions.EditPostView
import com.vitorpamplona.amethyst.ui.components.ClickableBox
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.note.ChangeReactionIcon
import com.vitorpamplona.amethyst.ui.note.QuickActionAlertDialog
import com.vitorpamplona.amethyst.ui.note.RenderReaction
import com.vitorpamplona.amethyst.ui.note.ZapReaction
import com.vitorpamplona.amethyst.ui.note.elements.AddHashtagLabelDialog
import com.vitorpamplona.amethyst.ui.note.elements.DropDownParams
import com.vitorpamplona.amethyst.ui.note.elements.NoteActionHandlers
import com.vitorpamplona.amethyst.ui.note.elements.ShareOptionsBottomSheet
import com.vitorpamplona.amethyst.ui.note.elements.noteActionSections
import com.vitorpamplona.amethyst.ui.note.elements.observeBookmarksFollowsAndAccount
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.report.ReportNoteDialog
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.DividerThickness
import com.vitorpamplona.amethyst.ui.theme.Size28Modifier
import com.vitorpamplona.amethyst.ui.theme.SmallishBorder
import com.vitorpamplona.amethyst.ui.theme.placeholderText
import com.vitorpamplona.amethyst.ui.theme.reactionBox
import com.vitorpamplona.amethyst.ui.theme.selectedReactionBoxModifier
import kotlinx.collections.immutable.toImmutableSet

/**
 * Long-press surface for a chat message: a quick-reaction row and the unpacked
 * zap amount presets on top, chat-only tiles (reply, edit draft), then the shared
 * note-action inventory (noteActionSections — the same one behind the 3-dot menu,
 * so the two surfaces can never drift) rendered as rows of icon tiles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMessageActionSheet(
    note: Note,
    onWantsToReply: (Note) -> Unit,
    onWantsToEditDraft: (Note) -> Unit,
    onDismiss: () -> Unit,
    accountViewModel: AccountViewModel,
    nav: INav,
    onWantsToEditChatMessage: ((Note) -> Unit)? = null,
) {
    var showShareSheet by remember { mutableStateOf(false) }
    var wantsToEditPost by remember { mutableStateOf(false) }
    var reportDialogShowing by remember { mutableStateOf(false) }
    var addLabelDialogShowing by remember { mutableStateOf(false) }
    var deleteConfirmationShowing by remember { mutableStateOf(false) }
    var showDeliveryDialog by remember { mutableStateOf(false) }

    // Own private rumors (NIP-17 DMs) must be retracted with a gift-wrapped
    // deletion — a public NIP-09 would e-tag the rumor id onto public relays.
    val performDelete = {
        if (note.isPrivateRumor()) {
            accountViewModel.deletePrivately(note)
        } else {
            accountViewModel.delete(note)
        }
    }

    if (deleteConfirmationShowing) {
        QuickActionAlertDialog(
            title = stringRes(R.string.quick_action_request_deletion_alert_title),
            textContent = stringRes(R.string.quick_action_request_deletion_alert_body),
            buttonIcon = MaterialSymbols.Delete,
            buttonText = stringRes(R.string.quick_action_delete_dialog_btn),
            onClickDoOnce = {
                performDelete()
                onDismiss()
            },
            onClickDontShowAgain = {
                performDelete()
                accountViewModel.account.settings.setHideDeleteRequestDialog()
                onDismiss()
            },
            onDismiss = { deleteConfirmationShowing = false },
        )
    }

    if (showShareSheet) {
        ShareOptionsBottomSheet(
            note = note,
            accountViewModel = accountViewModel,
            nav = nav,
            onDismiss = onDismiss,
        )
        return
    }

    if (showDeliveryDialog) {
        ChatMessageDeliveryDialog(
            baseNote = note,
            accountViewModel = accountViewModel,
            nav = nav,
            onDismiss = onDismiss,
        )
        return
    }

    if (wantsToEditPost) {
        EditPostView(
            onClose = onDismiss,
            edit = note,
            versionLookingAt = null,
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }

    if (reportDialogShowing) {
        ReportNoteDialog(note = note, accountViewModel = accountViewModel) {
            reportDialogShowing = false
            onDismiss()
        }
    }

    if (addLabelDialogShowing) {
        AddHashtagLabelDialog(
            note = note,
            accountViewModel = accountViewModel,
            onDismiss = {
                addLabelDialogShowing = false
                onDismiss()
            },
        )
    }

    val state by observeBookmarksFollowsAndAccount(note, accountViewModel).collectAsStateWithLifecycle(
        DropDownParams(
            isFollowingAuthor = false,
            isPrivateBookmarkNote = false,
            isPublicBookmarkNote = false,
            isPinnedNote = false,
            isLoggedUser = false,
            isSensitive = false,
            showSensitiveContent = null,
        ),
    )

    var showAllActions by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (!note.isDraft()) {
                QuickReactionRow(note, onDismiss, accountViewModel, nav)

                QuickStarGiftRow(note, accountViewModel, nav)

                SectionDivider()
            }

            // Stage one: the primary chat action (reply / edit draft) is always shown.
            ChatOnlyRow(note, state, onWantsToReply, onWantsToEditDraft, onDismiss)

            if (!note.isDraft()) {
                ReadAloudRow(note, accountViewModel, onDismiss)
            }

            val handlers =
                NoteActionHandlers(
                    onShare = { showShareSheet = true },
                    onEditPost = { wantsToEditPost = true },
                    onEditDraft = {
                        onWantsToEditDraft(note)
                        onDismiss()
                    },
                    onAddLabel = { addLabelDialogShowing = true },
                    onReport = { reportDialogShowing = true },
                    onDeleteRequest = {
                        if (accountViewModel.account.settings.hideDeleteRequestDialog) {
                            performDelete()
                            onDismiss()
                        } else {
                            deleteConfirmationShowing = true
                        }
                    },
                    onDismiss = onDismiss,
                )

            // Stage two: the full shared note-action inventory (the same one behind the
            // 3-dot menu, so the two surfaces never drift). Tucked behind a toggle so the
            // sheet opens compact — react, zap, reply — and only grows to full height
            // when the user asks for the rest.
            val sections =
                noteActionSections(
                    note = note,
                    noteVersionToCopy = note,
                    state = state,
                    handlers = handlers,
                    accountViewModel = accountViewModel,
                    nav = nav,
                )

            if (sections.isNotEmpty()) {
                SectionDivider()
                MoreActionsToggle(expanded = showAllActions, onToggle = { showAllActions = !showAllActions })

                if (showAllActions) {
                    sections.forEach { section ->
                        SectionDivider()
                        TileRow {
                            section.forEach { action ->
                                ActionTile(action.symbol, action.label, action.isDestructive, action.onClick)
                            }
                        }
                    }

                    // Chat-specific: which relays carry this message (and, for our own,
                    // their acceptance). A secondary action, so it lives under Show More.
                    if (!note.isDraft()) {
                        SectionDivider()
                        TileRow {
                            ActionTile(MaterialSymbols.DoneAll, stringRes(R.string.chat_delivery_details_title)) {
                                showDeliveryDialog = true
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The expand/collapse control between the always-visible quick actions and the full
 * action inventory. Keeps the sheet short on open (the common case is a reaction, a
 * zap, or a reply) and reveals everything else on demand.
 */
@Composable
private fun MoreActionsToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(SmallishBorder)
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringRes(if (expanded) R.string.show_less else R.string.more_options),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            symbol = if (expanded) MaterialSymbols.ExpandLess else MaterialSymbols.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Action tile sections ----------

/** Chat-specific tiles (reply, edit draft) that have no 3-dot menu equivalent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatOnlyRow(
    note: Note,
    state: DropDownParams,
    onWantsToReply: (Note) -> Unit,
    onWantsToEditDraft: (Note) -> Unit,
    onDismiss: () -> Unit,
) {
    if (note.isDraft() && !state.isLoggedUser) return
    if (note.isDraft() && state.isLoggedUser) {
        TileRow {
            ActionTile(MaterialSymbols.Edit, stringRes(R.string.edit_draft)) {
                onWantsToEditDraft(note)
                onDismiss()
            }
        }
        return
    }

    TileRow {
        ActionTile(MaterialSymbols.AutoMirrored.Chat, stringRes(R.string.reply_description)) {
            onWantsToReply(note)
            onDismiss()
        }
    }
}

/**
 * Read the message aloud with the system TTS engine. Lives in the long-press sheet instead of
 * a per-bubble speaker button, so a busy thread is not littered with identical icons.
 */
@Composable
private fun ReadAloudRow(
    note: Note,
    accountViewModel: AccountViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var text by remember(note.event?.id) { mutableStateOf(accountViewModel.cachedDecrypt(note)) }

    LaunchedEffect(note.event?.id) {
        accountViewModel.decrypt(note) {
            if (text != it) {
                text = it
            }
        }
    }

    val message = text
    if (message.isNullOrBlank()) return

    TileRow {
        ActionTile(MaterialSymbols.AutoMirrored.VolumeUp, stringRes(R.string.chat_read_aloud)) {
            TextToSpeechHelper
                .getInstance(context)
                .registerLifecycle(lifecycleOwner)
                .speak(message, note.idHex)
            onDismiss()
        }
    }
}

@Composable
private fun TileRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

@Composable
private fun ActionTile(
    symbol: MaterialSymbol,
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier =
            Modifier
                .width(80.dp)
                .clip(SmallishBorder)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            symbol = symbol,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SECTION_DIVIDER_ALPHA = 0.5f

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        thickness = DividerThickness,
        color = MaterialTheme.colorScheme.placeholderText.copy(alpha = SECTION_DIVIDER_ALPHA),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ---------- Quick reaction + zap rows ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickReactionRow(
    note: Note,
    onDismiss: () -> Unit,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    // A geohash chat provides these so a reaction (and its "selected" highlight) stays under the
    // anonymous per-cell identity; null (every other chat) keeps the standard account behavior.
    val onReactOverride = LocalChatReactOverride.current
    val actingIdentities = LocalChatActingIdentities.current
    val reactions by accountViewModel.reactionChoicesFlow().collectAsStateWithLifecycle()
    val myReactions =
        remember(note, actingIdentities) {
            if (actingIdentities != null) {
                note.reactions
                    .filterValues { reactors -> reactors.any { it.author?.pubkeyHex in actingIdentities } }
                    .keys
                    .toImmutableSet()
            } else {
                note.allReactionsByAuthor(accountViewModel.userProfile()).toImmutableSet()
            }
        }

    // Wrap onto as many rows as needed (like the zap grid) rather than a single
    // horizontally-scrolling strip, so long custom-reaction sets stay reachable.
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        reactions.forEach { reactionType ->
            ClickableBox(
                modifier = if (reactionType in myReactions) MaterialTheme.colorScheme.selectedReactionBoxModifier else reactionBox,
                onClick = {
                    onReactOverride?.invoke(note, reactionType) ?: accountViewModel.reactToOrDelete(note, reactionType)
                    onDismiss()
                },
            ) {
                RenderReaction(reactionType)
            }
        }

        ClickableBox(
            modifier = reactionBox,
            onClick = {
                nav.nav(Route.UpdateReactionType)
                onDismiss()
            },
        ) {
            ChangeReactionIcon(Size28Modifier, MaterialTheme.colorScheme.placeholderText)
        }
    }
}

/**
 * Star gifts in the official zap-grid slot of the long-press sheet.
 */
@Composable
private fun QuickStarGiftRow(
    note: Note,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZapReaction(
            baseNote = note,
            grayTint = MaterialTheme.colorScheme.placeholderText,
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }
}
