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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.activity

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.model.AddressableNote
import com.vitorpamplona.amethyst.commons.viewmodels.BroadcastUiState
import com.vitorpamplona.amethyst.commons.viewmodels.ConnectionUiState
import com.vitorpamplona.amethyst.commons.viewmodels.NestViewModel
import com.vitorpamplona.amethyst.commons.viewmodels.buildParticipantGrid
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.event.observeNoteEvent
import com.vitorpamplona.amethyst.ui.call.KeepScreenOn
import com.vitorpamplona.amethyst.ui.navigation.bottombars.KeyboardAwareBackHandler
import com.vitorpamplona.amethyst.ui.note.LoadAddressableNote
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.datasource.NestRoomFilterAssemblerSubscription
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle.AutoConnectAndTrackSpeakers
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle.LeaveOnKick
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle.LeaveOnRoomClosed
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle.NestForegroundServiceLifecycle
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle.NestPresencePublisher
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle.NestRoomEventCollectors
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle.PipBridge
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.lifecycle.rememberNestViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.screen.NestFullScreen
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.screen.NestPipScreen
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.screen.connectingLabel
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.nestsclient.NestsRoomConfig
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ROLE
import kotlinx.coroutines.flow.SharedFlow

/**
 * Top-level composable for [NestActivity]. Resolves the kind-30312
 * note from the address extra, then hands control to
 * [NestActivityBody].
 */
@Composable
internal fun NestActivityContent(
    addressValue: String,
    accountViewModel: AccountViewModel,
    isInPipMode: Boolean,
    isPipSupported: Boolean,
    onMuteState: (Boolean) -> Unit,
    onConnectedChange: (Boolean) -> Unit,
    pipMuteSignal: SharedFlow<Unit>,
    onLeave: () -> Unit,
    onMinimize: () -> Unit,
) {
    val parsedAddress = remember(addressValue) { Address.parse(addressValue) }
    if (parsedAddress == null) {
        // Malformed address — show the unjoinable state so the user
        // gets a clear path back instead of staring at a black screen
        // while the Activity sits behind a useless extra.
        UnjoinableRoomState(onLeave = onLeave)
        return
    }

    LoadAddressableNote(parsedAddress, accountViewModel) { addressableNote ->
        if (addressableNote == null) {
            // Note hasn't resolved yet — relay subscription is in
            // flight. Render a centered spinner so the user sees that
            // we're working on it (audit Android #6).
            LoadingRoomState()
            return@LoadAddressableNote
        }
        val event by observeNoteEvent<MeetingSpaceEvent>(addressableNote, accountViewModel)

        val current = event
        if (current == null) {
            LoadingRoomState()
            return@LoadAddressableNote
        }
        val service = current.service()
        val endPoint = current.endpoint()
        if (service.isNullOrBlank() || endPoint.isNullOrBlank()) {
            UnjoinableRoomState(onLeave = onLeave)
            return@LoadAddressableNote
        }
        val viewModel =
            rememberNestViewModel(
                NestsRoomConfig(
                    authBaseUrl = service,
                    endpoint = endPoint,
                    hostPubkey = current.pubKey,
                    roomId = current.dTag(),
                    kind = current.kind,
                ),
                accountViewModel.account.signer,
            )

        NestActivityBody(
            event = current,
            roomNote = addressableNote,
            viewModel = viewModel,
            accountViewModel = accountViewModel,
            isInPipMode = isInPipMode,
            isPipSupported = isPipSupported,
            onMuteState = onMuteState,
            onConnectedChange = onConnectedChange,
            pipMuteSignal = pipMuteSignal,
            onLeave = onLeave,
            onMinimize = onMinimize,
        )
    }
}

/**
 * Centered spinner shown between activity launch and the first
 * resolved kind-30312 event. Replaces the previous black screen so
 * the user knows the room is being loaded, not stuck.
 */
@Composable
private fun LoadingRoomState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringRes(R.string.nest_loading_room),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Terminal state for rooms whose kind-30312 doesn't carry the
 * service/endpoint pair the audio plane needs (or whose address is
 * malformed). Renders a clear explanation + a Back button so the
 * user can leave instead of force-killing the activity.
 */
@Composable
private fun UnjoinableRoomState(onLeave: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringRes(R.string.nest_unjoinable_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringRes(R.string.nest_unjoinable_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onLeave) {
                Text(stringRes(R.string.nest_unjoinable_back))
            }
        }
    }
}

/**
 * Per-room orchestration. Wires the [NestViewModel] to:
 *   - the relay subscription that feeds LocalCache
 *   - LocalCache → VM event collectors (presence, chat, reactions, kicks)
 *   - the system bridges (PIP, foreground service)
 *   - the kind-10312 presence publisher
 *
 * Each concern is one named composable from a sibling file. This
 * function stays a list of well-labelled wiring calls so the
 * room's lifecycle is readable end-to-end at a glance.
 */
@Composable
private fun NestActivityBody(
    event: MeetingSpaceEvent,
    roomNote: AddressableNote,
    viewModel: NestViewModel,
    accountViewModel: AccountViewModel,
    isInPipMode: Boolean,
    isPipSupported: Boolean,
    onMuteState: (Boolean) -> Unit,
    onConnectedChange: (Boolean) -> Unit,
    pipMuteSignal: SharedFlow<Unit>,
    onLeave: () -> Unit,
    onMinimize: () -> Unit,
) {
    val account = accountViewModel.account
    val localPubkey = account.signer.pubKey
    val roomATag = roomNote.idHex

    val ui by viewModel.uiState.collectAsState()
    val presences by viewModel.presences.collectAsState()

    // Single source of truth for "who is on the stage". Built from the
    // kind-30312 `p`-tags + the kind-10312 presence aggregator, with
    // the event author synthesised as the implicit HOST when they're
    // not in their own `p`-tags (per EGG-07; nostrnests' default).
    // This drives BOTH the StageGrid render AND the audio subscription
    // set, so the two stay in lockstep when a speaker (or the host)
    // emits `onstage=0` to step off the stage.
    val participantGrid =
        remember(event, presences) {
            buildParticipantGrid(
                participants = event.participants(),
                presences = presences,
                hostPubkey = event.pubKey,
                // Treat presence as authoritative only when fresher
                // than the role grant. nostrnests audience members
                // emit kind-10312 with `onstage=0` and never flip
                // it back on after a promotion — without this gate,
                // a freshly-promoted speaker would render in the
                // audience tab with role=SPEAKER, looking unchanged
                // to the host who just promoted them.
                roleGrantSec = event.createdAt,
                openMic = event.openMic(),
            )
        }
    val onStageKeys =
        remember(participantGrid) {
            participantGrid.onStage.map { it.pubkey }.toSet()
        }
    val isOnStageMe =
        remember(ui.micSeat, ui.onStageNow) {
            ui.micSeat != null && ui.onStageNow
        }
    val myTaggedRole =
        remember(event, localPubkey) {
            event.participants().firstOrNull { it.pubKey == localPubkey }?.effectiveRole()
        }

    AutoConnectAndTrackSpeakers(viewModel, onStageKeys)

    // Host "remove from mic" tags us PARTICIPANT — drop local seat
    // intent. Only act on a real demotion: a user who is *already* a
    // PARTICIPANT (e.g. an audience member who just tapped an empty
    // seat) must not be evicted the moment they take the seat, since
    // their tag was never a speaking role to begin with.
    var lastRole by remember(localPubkey) { mutableStateOf<ROLE?>(null) }
    LaunchedEffect(myTaggedRole, ui.micSeat) {
        val wasSpeaking = lastRole != null && lastRole != ROLE.PARTICIPANT
        val nowParticipant = myTaggedRole == ROLE.PARTICIPANT
        if (ui.micSeat != null && wasSpeaking && nowParticipant) {
            viewModel.leaveMicSeat()
            if (ui.broadcast is BroadcastUiState.Broadcasting || ui.broadcast == BroadcastUiState.Connecting) {
                viewModel.stopBroadcast()
            }
        }
        lastRole = myTaggedRole
    }

    // Auto-stop broadcast if we are no longer intentionally on a mic.
    LaunchedEffect(isOnStageMe, ui.broadcast) {
        if (!isOnStageMe &&
            (ui.broadcast is BroadcastUiState.Broadcasting || ui.broadcast == BroadcastUiState.Connecting)
        ) {
            viewModel.stopBroadcast()
            viewModel.leaveMicSeat()
        }
    }

    // Do not auto-ascend — the user must tap a mic seat intentionally.

    // Single REQ per relay covering chat, presence, reactions, admin
    // commands. See NestRoomFilterAssembler for the filter shape.
    NestRoomFilterAssemblerSubscription(roomNote, accountViewModel)

    // Read side: LocalCache → VM. Each kind has its own collector
    // (different VM hook per kind) plus the two eviction tickers.
    NestRoomEventCollectors(viewModel, event, roomATag, localPubkey)

    // Kick → leave the activity.
    LeaveOnKick(viewModel, onLeave)

    // Host ended the room (status: CLOSED) → leave the activity.
    // Same teardown path as kick — VM.onCleared() releases the
    // listener + speaker when the activity finishes.
    LeaveOnRoomClosed(event, onLeave)

    // Hold a screen-on lock while the user is actively in the room so the
    // device doesn't lock and interrupt the audio session UI.
    if (ui.connection is ConnectionUiState.Connected) {
        KeepScreenOn()
    }

    // System bridges: PIP overlay actions + foreground service.
    PipBridge(ui, pipMuteSignal, viewModel, onMuteState, onConnectedChange)
    NestForegroundServiceLifecycle(ui)

    // Tell NestActivity how to tear the session down when the user
    // closes PIP. Both close paths in PIP — the overlay's Leave
    // action and a swipe-dismiss while in PIP — invoke this so the
    // speaker handle close (releases AudioRecord and clears the
    // system mic indicator) and the listener close run on
    // cleanupScope/GlobalScope before the activity destruction
    // queue eats viewModelScope.
    val activity = LocalActivity.current as? NestActivity
    DisposableEffect(activity, viewModel) {
        activity?.setPipCleanupAction { viewModel.leave() }
        onDispose { activity?.setPipCleanupAction(null) }
    }

    // Hand-raise is screen-local UI state; presence-publish picks it
    // up via the heartbeat and emits onstage / muted / publishing
    // alongside.
    var handRaised by rememberSaveable(roomATag) { mutableStateOf(false) }
    NestPresencePublisher(
        account = account,
        event = event,
        ui = ui,
        handRaised = handRaised,
    )

    // Back gesture while connected = minimize to PIP rather than tearing
    // down the audio session. When disconnected (lobby / Connecting /
    // Failed) or PIP isn't supported, we let back fall through to the
    // default Activity.finish() so the user can cancel out cleanly.
    //
    // KeyboardAwareBackHandler steps aside while the soft keyboard is
    // up so back first dismisses the IME (the composer must be
    // dismissible — a plain BackHandler here swallowed that press and
    // minimized to PIP, stranding the keyboard on screen and trapping
    // the user in the composer). Once the IME is down, back minimizes
    // as before.
    val canMinimize = isPipSupported && !isInPipMode && ui.connection is ConnectionUiState.Connected
    KeyboardAwareBackHandler(enabled = canMinimize) { onMinimize() }

    // Loading gate: the room only mounts once the MoQ session has come
    // up at least once. Before that we show the dedicated "loading room"
    // screen so the user never enters a room that looks alive but has no
    // audio. After the first successful connect we keep the room mounted
    // across transient drops and layer a gentle reconnecting overlay on
    // top — tearing the screen down and rebuilding it on every blip
    // would lose chat scroll and the mic grid the user is looking at.
    var hasConnected by remember(roomNote) { mutableStateOf(false) }
    LaunchedEffect(ui.connection) {
        if (ui.connection is ConnectionUiState.Connected) hasConnected = true
    }

    if (isInPipMode) {
        NestPipScreen(
            title = event.room(),
            onStage = participantGrid.onStage,
            ui = ui,
            viewModel = viewModel,
            handRaised = handRaised,
            accountViewModel = accountViewModel,
        )
    } else if (hasConnected) {
        Box(modifier = Modifier.fillMaxSize()) {
            NestFullScreen(
                event = event,
                roomNote = roomNote,
                participantGrid = participantGrid,
                viewModel = viewModel,
                ui = ui,
                accountViewModel = accountViewModel,
                handRaised = handRaised,
                onHandRaisedChange = { handRaised = it },
                onLeave = onLeave,
                onMinimize = onMinimize,
                canMinimize = isPipSupported,
            )
            ConnectionOverlay(
                connection = ui.connection,
                onRetry = { viewModel.connect() },
                onLeave = onLeave,
            )
        }
    } else {
        RoomConnectingState(
            connection = ui.connection,
            onRetry = { viewModel.connect() },
            onLeave = onLeave,
        )
    }
}

/**
 * Gentle overlay shown over the live room when the transport drops and
 * the connector's retry loop is working ([ConnectionUiState.Reconnecting])
 * or has given up ([ConnectionUiState.Failed]). Renders nothing while the
 * session is healthy so it stays out of the way.
 */
@Composable
private fun ConnectionOverlay(
    connection: ConnectionUiState,
    onRetry: () -> Unit,
    onLeave: () -> Unit,
) {
    if (connection is ConnectionUiState.Connected) return
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                if (connection is ConnectionUiState.Failed) {
                    Text(
                        text = stringRes(R.string.nest_unjoinable_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = connection.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onLeave) {
                            Text(stringRes(R.string.nest_leave))
                        }
                        Button(onClick = onRetry) {
                            Text(stringRes(R.string.nest_retry))
                        }
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        text = stringRes(R.string.nest_reconnecting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedButton(onClick = onLeave) {
                        Text(stringRes(R.string.nest_leave))
                    }
                }
            }
        }
    }
}

/**
 * Full-screen "loading room" shown until [NestViewModel]'s MoQ session
 * reports [ConnectionUiState.Connected]. Carries the live connect step
 * so the user sees progress ("Resolving room" → "Opening transport" →
 * "Negotiating audio"), and turns into a gentle retry card if the
 * handshake lands in [ConnectionUiState.Failed]. This is the gate that
 * keeps the room from opening before everything inside is ready.
 */
@Composable
private fun RoomConnectingState(
    connection: ConnectionUiState,
    onRetry: () -> Unit,
    onLeave: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            if (connection is ConnectionUiState.Failed) {
                Text(
                    text = stringRes(R.string.nest_unjoinable_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = connection.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onLeave) {
                        Text(stringRes(R.string.nest_leave))
                    }
                    Button(onClick = onRetry) {
                        Text(stringRes(R.string.nest_retry))
                    }
                }
            } else {
                CircularProgressIndicator()
                Text(
                    text = stringRes(R.string.nest_loading_room),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val stepLabel =
                    when (connection) {
                        is ConnectionUiState.Connecting -> connectingLabel(connection)
                        is ConnectionUiState.Reconnecting -> stringRes(R.string.nest_reconnecting)
                        else -> null
                    }
                stepLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onLeave) {
                    Text(stringRes(R.string.nest_leave))
                }
            }
        }
    }
}
