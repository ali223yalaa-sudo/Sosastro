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
package com.vitorpamplona.amethyst.ui.note.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbol
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.ui.components.UserAvatar
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.model.User
import com.vitorpamplona.amethyst.ui.components.toasts.ToastManager
import com.vitorpamplona.amethyst.ui.components.util.setText
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.note.externalLinkForNote
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource.firstReelVideoMeta
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ShareSendState { Idle, Sending, Sent, Failed }

/**
 * Instagram-Reels-style share sheet: a draggable bottom drawer with a searchable
 * friends row (multi-select), the external share options (WhatsApp, system share,
 * download, copy link, add to story) and a send button for the in-app DM send.
 *
 * Opened from every note Share button (reel side rail, feed reaction row, 3-dot
 * menu). The reel keeps playing untouched while this sheet is up: nothing here
 * reads or resets the player state, and [ModalBottomSheet] renders in its own
 * window, so opening/closing never restarts the video.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareReelSheet(
    note: Note,
    accountViewModel: AccountViewModel,
    nav: INav,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toastManager = accountViewModel.toastManager

    val reelMeta = remember(note.idHex) { note.event?.firstReelVideoMeta() }
    val shareLink = remember(note.idHex, note.event) { externalLinkForNote(note) }
    val nostrUri = remember(note.idHex, note.event) { note.toNostrUri() }

    val account = accountViewModel.account
    val follows by account.kind3FollowList.userList.collectAsStateWithLifecycle()
    // Friends most recently chatted with float to the top, the way Instagram orders
    // its share sheet; everyone else falls back to alphabetical.
    val friends =
        remember(follows) {
            val recency = account.chatroomList.mostRecentMessageTimeByPartner()
            follows
                .filter { !account.isHidden(it) }
                .sortedWith(
                    compareByDescending<User> { recency[it.pubkeyHex] ?: 0L }
                        .thenBy { it.toBestDisplayName().lowercase() },
                )
        }

    var query by remember { mutableStateOf("") }
    val filteredFriends =
        remember(friends, query) {
            if (query.isBlank()) {
                friends
            } else {
                friends.filter {
                    it.toBestDisplayName().contains(query, ignoreCase = true) ||
                        it.pubkeyHex.contains(query, ignoreCase = true)
                }
            }
        }

    val selected = remember { mutableStateMapOf<String, User>() }
    var sendState by remember { mutableStateOf(ShareSendState.Idle) }
    val isOnline = remember { mutableStateOf(context.isCurrentlyOnline()) }

    fun dismiss() {
        if (sendState == ShareSendState.Sending) return
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { SheetDragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            Text(
                text = stringRes(R.string.share_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringRes(R.string.share_search_people)) },
                leadingIcon = {
                    Icon(
                        symbol = MaterialSymbols.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                shape = RoundedCornerShape(28.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(12.dp))

            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                SelectedRecipientsRow(
                    selected = selected.values.toList(),
                    onRemove = { selected.remove(it.pubkeyHex) },
                )
            }

            FriendsRow(
                friends = filteredFriends,
                hasAnyFriend = friends.isNotEmpty(),
                selected = selected,
                onToggle = { user ->
                    if (selected.containsKey(user.pubkeyHex)) {
                        selected.remove(user.pubkeyHex)
                    } else {
                        selected[user.pubkeyHex] = user
                    }
                    if (sendState == ShareSendState.Sent || sendState == ShareSendState.Failed) {
                        sendState = ShareSendState.Idle
                    }
                },
                onAddPeople = {
                    onDismiss()
                    nav.nav(Route.Search)
                },
            )

            Spacer(Modifier.height(8.dp))
            SheetDivider()
            Spacer(Modifier.height(4.dp))

            ExternalOptionsRow(
                onWhatsApp = {
                    shareViaPackage(
                        context = context,
                        packageName = "com.whatsapp",
                        text = shareLink,
                        toastManager = toastManager,
                    )
                },
                onSystemShare = {
                    openSystemShare(context, shareLink, toastManager)
                },
                onDownload = {
                    if (reelMeta != null) {
                        accountViewModel.saveMediaToGallery(reelMeta.url, reelMeta.mimeType, context)
                    } else {
                        toastManager.toast(R.string.share_sheet_title, R.string.share_no_video)
                    }
                },
                onCopyLink = {
                    scope.launch {
                        clipboard.setText(shareLink)
                        toastManager.toast(R.string.share_sheet_title, R.string.share_link_copied)
                    }
                },
                onAddToStory = {
                    if (reelMeta != null) {
                        accountViewModel.launchSigner {
                            account.sendEphemeralStoryFromUrl(
                                url = reelMeta.url,
                                mimeType = reelMeta.mimeType,
                                hash = reelMeta.hash,
                                caption = null,
                            )
                        }
                        toastManager.toast(R.string.share_sheet_title, R.string.share_story_published)
                    } else {
                        toastManager.toast(R.string.share_sheet_title, R.string.share_no_video)
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                SendButton(
                    count = selected.size,
                    state = sendState,
                    enabled = accountViewModel.isWriteable() && isOnline.value,
                    onClick = {
                        if (sendState == ShareSendState.Sending) return@SendButton
                        sendState = ShareSendState.Sending
                        val recipients = selected.values.toList()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    recipients.forEach { user ->
                                        account.sendNip17PrivateMessage(
                                            ChatMessageEvent.build(nostrUri, listOf(user.toPTag())),
                                        )
                                    }
                                }
                                sendState = ShareSendState.Sent
                                toastManager.toast(R.string.share_sent_title, R.string.share_sent_message)
                                delay(500)
                                onDismiss()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                sendState = ShareSendState.Failed
                                toastManager.toast(R.string.share_failed_title, e.message ?: "", e)
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun SheetDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

@Composable
private fun SelectedRecipientsRow(
    selected: List<User>,
    onRemove: (User) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(selected, key = { it.pubkeyHex }) { user ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 72.dp)) {
                Box {
                    UserAvatar(
                        userHex = user.pubkeyHex,
                        pictureUrl = user.profilePicture(),
                        size = 56.dp,
                        contentDescription = user.toBestDisplayName(),
                    )
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                                .clickable { onRemove(user) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            symbol = MaterialSymbols.Close,
                            contentDescription = stringRes(R.string.share_selected),
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = user.toBestDisplayName(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FriendsRow(
    friends: List<User>,
    hasAnyFriend: Boolean,
    selected: Map<String, User>,
    onToggle: (User) -> Unit,
    onAddPeople: () -> Unit,
) {
    when {
        !hasAnyFriend -> EmptyHint(text = stringRes(R.string.share_no_friends), onAddPeople = onAddPeople)

        friends.isEmpty() -> EmptyHint(text = stringRes(R.string.share_no_results), onAddPeople = null)

        else ->
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "__add_people__") { AddPeopleItem(onAddPeople) }

                items(friends, key = { it.pubkeyHex }) { user ->
                    FriendItem(
                        user = user,
                        isSelected = selected.containsKey(user.pubkeyHex),
                        onToggle = { onToggle(user) },
                    )
                }
            }
    }
}

@Composable
private fun AddPeopleItem(onAddPeople: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = 72.dp).clickable { onAddPeople() },
    ) {
        Box(
            modifier =
                Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                symbol = MaterialSymbols.PersonAdd,
                contentDescription = stringRes(R.string.share_add_people),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringRes(R.string.share_add_people),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FriendItem(
    user: User,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val ringAlpha by animateFloatAsState(if (isSelected) 1f else 0f, label = "shareRing")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = 72.dp).clickable { onToggle() },
    ) {
        Box {
            UserAvatar(
                userHex = user.pubkeyHex,
                pictureUrl = user.profilePicture(),
                size = 62.dp,
                contentDescription = user.toBestDisplayName(),
            )
            if (ringAlpha > 0f) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f * ringAlpha)),
                )
            }
            if (isSelected) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        symbol = MaterialSymbols.Check,
                        contentDescription = stringRes(R.string.share_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = user.toBestDisplayName(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyHint(
    text: String,
    onAddPeople: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onAddPeople != null) {
            Button(onClick = onAddPeople) {
                Icon(symbol = MaterialSymbols.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringRes(R.string.share_add_people))
            }
        }
    }
}

@Composable
private fun ExternalOptionsRow(
    onWhatsApp: () -> Unit,
    onSystemShare: () -> Unit,
    onDownload: () -> Unit,
    onCopyLink: () -> Unit,
    onAddToStory: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { ExternalOption(MaterialSymbols.Chat, stringRes(R.string.share_whatsapp), onWhatsApp) }
        item { ExternalOption(MaterialSymbols.Share, stringRes(R.string.share_system), onSystemShare) }
        item { ExternalOption(MaterialSymbols.Download, stringRes(R.string.share_download), onDownload) }
        item { ExternalOption(MaterialSymbols.Link, stringRes(R.string.share_copy_link), onCopyLink) }
        item { ExternalOption(MaterialSymbols.AddCircle, stringRes(R.string.share_add_to_story), onAddToStory) }
    }
}

@Composable
private fun ExternalOption(
    symbol: MaterialSymbol,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable { onClick() },
    ) {
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                symbol = symbol,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SendButton(
    count: Int,
    state: ShareSendState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && state != ShareSendState.Sending && state != ShareSendState.Sent,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
    ) {
        when (state) {
            ShareSendState.Sending -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text(stringRes(R.string.share_sending))
            }

            ShareSendState.Sent -> {
                Icon(symbol = MaterialSymbols.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringRes(R.string.share_sent_title))
            }

            else -> Text(stringRes(R.string.share_send_to_count, count.toString()))
        }
    }
}

private fun Context.isCurrentlyOnline(): Boolean =
    runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.activeNetwork != null
    }.getOrDefault(true)

private fun shareViaPackage(
    context: Context,
    packageName: String,
    text: String,
    toastManager: ToastManager,
) {
    try {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage(packageName)
            }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toastManager.toast(R.string.share_sheet_title, R.string.share_app_not_found)
    } catch (e: Exception) {
        toastManager.toast(R.string.share_sheet_title, R.string.share_app_not_found)
    }
}

private fun openSystemShare(
    context: Context,
    text: String,
    toastManager: ToastManager,
) {
    try {
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(Intent.createChooser(sendIntent, null))
    } catch (e: ActivityNotFoundException) {
        toastManager.toast(R.string.share_sheet_title, R.string.share_app_not_found)
    }
}
