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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.viewmodels.BroadcastUiState
import com.vitorpamplona.amethyst.commons.viewmodels.ConnectionUiState
import com.vitorpamplona.amethyst.commons.viewmodels.NestUiState
import com.vitorpamplona.amethyst.commons.viewmodels.NestViewModel
import com.vitorpamplona.amethyst.model.AddressableNote
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts.RoomGiftPanel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts.sendRoomGift
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.reactions.RoomReactionPopup
import com.vitorpamplona.amethyst.ui.stringRes

/**
 * Sticky bottom action bar for the room screen.
 *
 * Layout: `[ start cluster ] · · · [ end cluster ]` with an optional
 * red status strip above for connection / broadcast / mute failures.
 *
 * Start cluster — driven by connection state only:
 *
 * | State                              | Start cluster contents |
 * |------------------------------------|------------------------|
 * | Idle / Closed / Failed connection  | `[Connect]`            |
 * | Connecting                         | status chip            |
 * | Reconnecting                       | status chip            |
 * | Connected                          | empty                  |
 *
 * On-stage controls (Talk / MicMute / Leave the Stage) live in
 * [StageControlsBar], attached to the bottom of the Stage card.
 *
 * End cluster: hand-raise (audience + connected only), react, leave room.
 */
@Composable
internal fun NestActionBar(
    viewModel: NestViewModel,
    ui: NestUiState,
    isOnStage: Boolean,
    handRaised: Boolean,
    onHandRaisedChange: (Boolean) -> Unit,
    roomNote: AddressableNote,
    accountViewModel: AccountViewModel,
    nav: INav,
    onLeave: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ActionBarStatusStrip(ui = ui)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    StartCluster(viewModel = viewModel, ui = ui)
                }
                EndCluster(
                    isOnStage = isOnStage,
                    isConnected = ui.connection is ConnectionUiState.Connected,
                    handRaised = handRaised,
                    onHandRaisedChange = onHandRaisedChange,
                    roomNote = roomNote,
                    accountViewModel = accountViewModel,
                    nav = nav,
                    onLeave = onLeave,
                )
            }
        }
    }
}

/**
 * Controls strip that attaches under the Stage card. Leave-the-stage
 * is intentionally omitted — leaving the room is the only exit.
 * Mic mute lives in [NestRoomFooter] next to room listen-mute.
 * Broadcast auto-start is [AutoStartOnMicBroadcast].
 */
@Composable
internal fun StageControlsBar(
    viewModel: NestViewModel,
    ui: NestUiState,
    isOnStage: Boolean,
    canBroadcast: Boolean,
    speakerPubkeyHex: String,
    modifier: Modifier = Modifier,
) {
    // Kept as a no-op slot so older call sites compile; layout moved.
}

/** Single-line red error strip. Surfaces the most relevant failure. */
@Composable
internal fun ActionBarStatusStrip(ui: NestUiState) {
    val text = ui.statusStripText() ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun NestUiState.statusStripText(): String? {
    val connection = connection
    if (connection is ConnectionUiState.Failed) {
        return stringRes(R.string.nest_audio_failed, connection.reason)
    }
    return when (val b = broadcast) {
        is BroadcastUiState.Failed -> stringRes(R.string.nest_broadcast_failed, b.reason)
        is BroadcastUiState.Broadcasting -> b.muteError?.let { stringRes(R.string.nest_mute_failed, it) }
        else -> null
    }
}

@Composable
internal fun StartCluster(
    viewModel: NestViewModel,
    ui: NestUiState,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val connection = ui.connection) {
            is ConnectionUiState.Idle,
            is ConnectionUiState.Closed,
            is ConnectionUiState.Failed,
            -> {
                ConnectButton(onClick = viewModel::connect)
            }

            is ConnectionUiState.Connecting -> {
                StatusChip(label = connectingLabel(connection))
            }

            is ConnectionUiState.Reconnecting -> {
                StatusChip(label = stringRes(R.string.nest_reconnecting))
            }

            // On-stage controls live in [StageControlsBar]; audience
            // has nothing to do here (system volume keys are enough).
            is ConnectionUiState.Connected -> {}
        }
    }
}

@Composable
internal fun AutoStartOnMicBroadcast(
    viewModel: NestViewModel,
    ui: NestUiState,
    isOnMic: Boolean,
    speakerPubkeyHex: String,
) {
    if (!isOnMic || !viewModel.canBroadcast) return
    val context = LocalContext.current
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.startBroadcast(speakerPubkeyHex, initialMuted = true)
        }
    LaunchedEffect(isOnMic, speakerPubkeyHex, ui.broadcast) {
        if (!isOnMic) return@LaunchedEffect
        if (ui.broadcast != BroadcastUiState.Idle) return@LaunchedEffect
        if (context.hasMicPermission()) {
            viewModel.startBroadcast(speakerPubkeyHex, initialMuted = true)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun EndCluster(
    isOnStage: Boolean,
    isConnected: Boolean,
    handRaised: Boolean,
    onHandRaisedChange: (Boolean) -> Unit,
    roomNote: AddressableNote,
    accountViewModel: AccountViewModel,
    nav: INav,
    onLeave: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Hand-raise: only meaningful for connected audience. On stage
        // it's moot; disconnected it can't reach the room.
        if (!isOnStage && isConnected) {
            HandRaiseToggle(handRaised = handRaised, onToggle = onHandRaisedChange)
        }
        // Zap and React both work in any state — even disconnected
        // users can zap / react via the room note.
        NestZapButton(
            roomNote = roomNote,
            accountViewModel = accountViewModel,
            nav = nav,
        )
        // React works in any state — even disconnected users can react
        // via the room note. Reuses the same ReactionChoicePopup
        // NoteCompose's heart button drives (NIP-30 custom-emoji
        // support, user-configured reaction set). Earlier hand-rolled
        // bottom-sheet picker is gone — it only emitted a fixed set
        // of six unicode emojis with no path to custom packs.
        var wantsToReact by rememberSaveable { mutableStateOf(false) }
        FilledTonalIconButton(onClick = { wantsToReact = true }) {
            Icon(
                symbol = MaterialSymbols.EmojiEmotions,
                contentDescription = stringRes(R.string.nest_reactions_button),
            )
            if (wantsToReact) {
                RoomReactionPopup(
                    roomNote = roomNote,
                    iconSize = 24.dp,
                    accountViewModel = accountViewModel,
                    onDismiss = { wantsToReact = false },
                    // No `Route.UpdateReactionType` from inside the
                    // standalone NestActivity (no NavController). The
                    // "change reactions" button still dismisses the
                    // popup; users edit their reaction set from the
                    // main app's settings.
                    onChangeAmount = { wantsToReact = false },
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        LeaveRoomButton(onClick = onLeave)
    }
}

/**
 * Round gift button. Opens the room gift panel (the TUILiveKit
 * `GiftListView` analogue) — no wallet, no server: sending a gift
 * publishes a kind-7 with a `gift` tag.
 */
@Composable
internal fun NestZapButton(
    roomNote: AddressableNote,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    var openGifts by remember { mutableStateOf(false) }

    IconButton(onClick = { openGifts = true }) {
        Icon(
            symbol = MaterialSymbols.Star,
            contentDescription = stringRes(R.string.stars_gift_send),
            tint = Color(0xFFFFD54F),
            modifier = Modifier.size(32.dp),
        )
    }

    if (openGifts) {
        RoomGiftPanel(
            toLabel = roomNote.author?.toBestDisplayName(),
            clientBuilder = accountViewModel.httpClientBuilder,
            onSend = { sku, quantity ->
                sendRoomGift(
                    accountViewModel = accountViewModel,
                    roomNote = roomNote,
                    sku = sku,
                    quantity = quantity,
                    targetPubkey = roomNote.author?.pubkeyHex,
                )
            },
            onDismiss = { openGifts = false },
        )
    }
}

// ── Reusable affordances ────────────────────────────────────────────────

@Composable
private fun ConnectButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(stringRes(R.string.nest_connect))
    }
}

/** Disabled assist chip used as a status indicator (no click target). */
@Composable
private fun StatusChip(label: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
    )
}

@Composable
private fun HandRaiseToggle(
    handRaised: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FilledTonalIconToggleButton(
        checked = handRaised,
        onCheckedChange = onToggle,
    ) {
        Icon(
            symbol = MaterialSymbols.PanTool,
            contentDescription = stringRes(if (handRaised) R.string.nest_lower_hand else R.string.nest_raise_hand),
        )
    }
}

@Composable
private fun LeaveRoomButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
    ) {
        Icon(
            symbol = MaterialSymbols.AutoMirrored.Logout,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(6.dp))
        Text(stringRes(R.string.nest_leave))
    }
}

@Composable
internal fun connectingLabel(connection: ConnectionUiState.Connecting): String =
    when (connection.step) {
        ConnectionUiState.Step.ResolvingRoom -> stringRes(R.string.nest_connecting_resolving)
        ConnectionUiState.Step.OpeningTransport -> stringRes(R.string.nest_connecting_transport)
        ConnectionUiState.Step.MoqHandshake -> stringRes(R.string.nest_connecting_handshake)
    }

// ── Permission helpers ──────────────────────────────────────────────────

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}
