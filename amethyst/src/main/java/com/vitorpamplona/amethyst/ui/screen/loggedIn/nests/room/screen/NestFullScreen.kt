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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.viewmodels.NestUiState
import com.vitorpamplona.amethyst.commons.viewmodels.NestViewModel
import com.vitorpamplona.amethyst.commons.viewmodels.ParticipantGrid
import com.vitorpamplona.amethyst.commons.viewmodels.resolveMicSeats
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.ui.navigation.navs.BouncingIntentNav
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.chat.NestChatPanel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.chat.NestEditFieldRow
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.chat.NestNewMessageViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.edit.EditNestSheet
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.edit.EditNestViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts.RoomGiftOverlay
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts.RoomGiftTicker
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.participants.ParticipantHostActionsSheet
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.participants.RoomParticipantActions
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.participants.openProfileInMainActivity
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.reactions.RoomReactionPopup
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.stage.AudienceGrid
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.stage.HandRaiseQueueSection
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.stage.HostMicSeatAuthority
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.stage.JoinBanner
import com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.stage.MicSeatsGrid
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip19Bech32.toNAddr
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.tags.StatusTag
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ROLE
import kotlinx.coroutines.launch

/**
 * Full-screen layout for [com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.activity.NestActivity]. Vertically split into:
 *
 *   1. TopAppBar — room title (tap to expand/collapse summary) + overflow
 *      menu (Share, host's Edit).
 *   2. Optional summary strip — only visible when the user taps the title.
 *   3. Stage — vertical adaptive grid of host/speakers, with the LIVE
 *      chip and listener count rendered in the stage header row
 *      (height-bounded so a 30-speaker room scrolls inside the strip
 *      and never pushes the chat below the fold).
 *   4. Tabs — `Chat | Audience · N | Hands · N` (Hands host-only,
 *      shown only while there's at least one raised hand).
 *   5. Tab content — fills the remaining vertical space:
 *        - Chat: the kind-1311 transcript + composer
 *        - Audience: lazy vertical grid (handles 1,000+ listeners)
 *        - Hands: host's promote-to-speaker queue
 *   6. Sticky action bar — connection / talk / hand / react / leave
 *      controls; never scrolls.
 *
 * The PIP variant lives in [NestPipScreen]; the activity flips
 * between them based on `isInPipMode`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NestFullScreen(
    event: MeetingSpaceEvent,
    roomNote: com.vitorpamplona.amethyst.model.AddressableNote,
    participantGrid: ParticipantGrid,
    viewModel: NestViewModel,
    ui: NestUiState,
    accountViewModel: AccountViewModel,
    handRaised: Boolean,
    onHandRaisedChange: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onMinimize: () -> Unit,
    canMinimize: Boolean,
) {
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var showHostMenu by rememberSaveable { mutableStateOf(false) }
    var showHostLeaveConfirm by rememberSaveable { mutableStateOf(false) }
    var hostMenuTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // Summary is collapsed by default; tapping the top-bar title
    // toggles it so the user can preview the room description without
    // opening a separate sheet, then collapse again to reclaim vertical
    // room for the stage and chat.
    var summaryExpanded by rememberSaveable { mutableStateOf(false) }
    var showAudienceSheet by rememberSaveable { mutableStateOf(false) }
    var wantsToReact by rememberSaveable { mutableStateOf(false) }

    val isHost = accountViewModel.account.signer.pubKey == event.pubKey
    val myPubkey = accountViewModel.account.signer.pubKey
    val myRole =
        remember(event, myPubkey) {
            event.participants().firstOrNull { it.pubKey == myPubkey }?.effectiveRole()
        }
    val isHostOrMod = isHost || myRole == ROLE.MODERATOR
    val canSpeak = isHostOrMod || myRole == ROLE.SPEAKER
    val openMic = remember(event) { event.openMic() }
    val leaveScope = rememberCoroutineScope()
    val topBarContext = LocalContext.current
    // BouncingIntentNav handles the few routes the zap button can reach
    // (UpdateZapAmount, ManualZapSplitPayment) by bouncing through
    // MainActivity — same mechanism the NestChatPanel uses for tap-on-
    // mention nav. Routes that don't map to a `nostr:` URI are no-ops.
    val actionBarNav = remember(topBarContext, leaveScope) { BouncingIntentNav(topBarContext, leaveScope) }

    val presences by viewModel.presences.collectAsState()
    val notices by viewModel.roomNotices.collectAsState()
    val reactionsByPubkey by viewModel.recentReactions.collectAsState()
    val zapsByPubkey by viewModel.recentZaps.collectAsState()
    val giftsByTarget by viewModel.recentGifts.collectAsState()
    val speakerCatalogs by viewModel.speakerCatalogs.collectAsState()
    val audioLevels by viewModel.audioLevels.collectAsState()

    // Single back owner for the room. While the composer has focus (the
    // keyboard is up) back dismisses the keyboard and nothing else; once
    // focus/IME is gone, back minimizes to PIP only while the session is
    // actually connected — during a drop it falls through to
    // Activity.finish() so the user can back out of a reconnecting room.
    // Tracking focus directly — rather than the IME inset — makes the
    // keyboard half work on every Android version, edge-to-edge or not.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var composerFocused by remember { mutableStateOf(false) }
    val backMinimizes = canMinimize && ui.connection is com.vitorpamplona.amethyst.commons.viewmodels.ConnectionUiState.Connected
    BackHandler(enabled = composerFocused || backMinimizes) {
        if (composerFocused) {
            focusManager.clearFocus()
            keyboard?.hide()
            composerFocused = false
        } else {
            onMinimize()
        }
    }

    val onStageKeys =
        remember(participantGrid) {
            participantGrid.onStage.map { it.pubkey }.toSet()
        }
    val lockedSeats = remember(event) { event.lockedMicSeats() }
    val seatOccupants =
        remember(event, presences, ui.micSeat, ui.onStageNow, myPubkey) {
            val resolved =
                resolveMicSeats(
                    hostSeats = event.micSeats(),
                    presences = presences,
                ).toMutableMap()
            // Local optimistic seat — show me immediately even before presence/host sync.
            val local = ui.micSeat
            if (local != null && ui.onStageNow) {
                resolved.entries.removeAll { it.value == myPubkey }
                if (resolved[local] == null || resolved[local] == myPubkey) {
                    resolved[local] = myPubkey
                }
            }
            resolved
        }
    // On-mic = explicit seat claim only (no auto-ascend from HOST role).
    val isOnStageMe =
        remember(ui.micSeat, ui.onStageNow) {
            ui.micSeat != null && ui.onStageNow
        }
    // Host assigned us a seat (approve / claim) — mirror into local presence so Talk starts muted.
    val hostAssignedSeat =
        remember(event.micSeats(), myPubkey) {
            event
                .micSeats()
                .entries
                .firstOrNull { it.value == myPubkey }
                ?.key
        }
    LaunchedEffect(hostAssignedSeat, myRole) {
        if (hostAssignedSeat != null && myRole != ROLE.PARTICIPANT) {
            viewModel.takeMicSeat(hostAssignedSeat)
        }
    }

    HostMicSeatAuthority(
        enabled = isHost,
        event = event,
        hostPubkey = event.pubKey,
        openMic = openMic,
        lockedSeats = lockedSeats,
        presences = presences,
        accountViewModel = accountViewModel,
    )
    val mutedPubkeys =
        remember(presences, ui.broadcast, myPubkey) {
            buildSet {
                presences.forEach { (pk, p) -> if (p.muted == true) add(pk) }
                val b = ui.broadcast
                if (b is com.vitorpamplona.amethyst.commons.viewmodels.BroadcastUiState.Broadcasting && b.isMuted) {
                    add(myPubkey)
                }
            }
        }
    // Same logic HandRaiseQueueSection uses internally — duplicated
    // here so the tab label can show a count without coupling the
    // section to the screen.
    val handsCount =
        remember(presences, onStageKeys) {
            presences.values.count { it.handRaised && it.pubkey !in onStageKeys }
        }
    val showHandsTab = isHost && handsCount > 0
    val presentPeople =
        remember(participantGrid) {
            (participantGrid.onStage + participantGrid.audience).filter { !it.absent }
        }
    val micMutedNow =
        (ui.broadcast as? com.vitorpamplona.amethyst.commons.viewmodels.BroadcastUiState.Broadcasting)?.isMuted
            ?: true

    AutoStartOnMicBroadcast(
        viewModel = viewModel,
        ui = ui,
        isOnMic = isOnStageMe,
        speakerPubkeyHex = myPubkey,
    )

    // Long-press on any participant opens the host-actions sheet
    // (T2 #2). The sheet itself gates which rows render based on
    // host status. Skip self.
    val onLongPressParticipant: ((String) -> Unit) = { target ->
        if (target != myPubkey) hostMenuTarget = target
    }

    fun publishRoomTemplate(template: com.vitorpamplona.quartz.nip01Core.signers.EventTemplate<MeetingSpaceEvent>) {
        accountViewModel.launchSigner {
            accountViewModel.account.signAndComputeBroadcast(template)
        }
    }

    fun leaveRoom() {
        if (isHost) {
            viewModel.leaveMicSeat()
            showHostLeaveConfirm = true
        } else {
            leaveScope.launch {
                viewModel.leaveMicSeat()
                publishGonePresence(accountViewModel, event)
                viewModel.clearChat()
                viewModel.leave()
                onLeave()
            }
        }
    }

    val nestChatModel: NestNewMessageViewModel =
        androidx.lifecycle.viewmodel.compose
            .viewModel(key = "Nest/${event.address().toValue()}")
    nestChatModel.init(accountViewModel)
    nestChatModel.load(roomNote)

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Black,
        contentColor = Color.White,
        bottomBar = {
            Column {
                ActionBarStatusStrip(ui)
                StartCluster(viewModel, ui)
                NestRoomFooter(
                    isMuted = ui.isMuted,
                    onToggleMute = { viewModel.setMuted(!ui.isMuted) },
                    showMicMute = isOnStageMe,
                    isMicMuted = micMutedNow,
                    onToggleMicMute = { viewModel.setMicMuted(!micMutedNow) },
                    roomNote = roomNote,
                    accountViewModel = accountViewModel,
                    nav = actionBarNav,
                    composer = {
                        NestEditFieldRow(
                            nestScreenModel = nestChatModel,
                            accountViewModel = accountViewModel,
                            onSendNewMessage = {},
                            nav = actionBarNav,
                            onFocusChanged = { composerFocused = it },
                        )
                    },
                )
            }
        },
    ) { padding ->
        // Tapping empty space anywhere in the room dismisses the
        // composer's keyboard, matching the user's expectation and the
        // platform norm. A `pointerInput` tap detector only fires for
        // taps that no child consumed (buttons, seats, chat rows all
        // consume their own), so this never swallows a real tap.
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                focusManager.clearFocus()
                                keyboard?.hide()
                            },
                        )
                    },
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                NestRoomHeader(
                    title = event.room().orEmpty(),
                    imageUrl = event.image(),
                    isHost = isHost,
                    openMic = openMic,
                    showHostMenu = showHostMenu,
                    showMinimize = canMinimize,
                    onMinimize = onMinimize,
                    onMenuOpen = { showHostMenu = true },
                    onMenuDismiss = { showHostMenu = false },
                    onShare = {
                        showHostMenu = false
                        shareRoomNaddr(topBarContext, event)
                    },
                    onEdit = {
                        showHostMenu = false
                        showEditSheet = true
                    },
                    onToggleOpenMic = {
                        showHostMenu = false
                        publishRoomTemplate(RoomParticipantActions.setOpenMic(event, !openMic))
                    },
                    onLeave = { leaveRoom() },
                    handRaised = handRaised,
                    showHandRaise = !isOnStageMe && ui.connection is com.vitorpamplona.amethyst.commons.viewmodels.ConnectionUiState.Connected,
                    onToggleHand = { onHandRaisedChange(!handRaised) },
                    onReact = {
                        showHostMenu = false
                        wantsToReact = true
                    },
                )
                if (summaryExpanded) {
                    RoomSummaryStrip(summary = event.summary())
                }
                NestAudiencePreviewRow(
                    presentCount = presentPeople.size,
                    previewMembers = presentPeople,
                    accountViewModel = accountViewModel,
                    onOpenList = { showAudienceSheet = true },
                )
                val onTapSelf: (() -> Unit)? =
                    (ui.broadcast as? com.vitorpamplona.amethyst.commons.viewmodels.BroadcastUiState.Broadcasting)?.let { state ->
                        { viewModel.setMicMuted(!state.isMuted) }
                    }
                // The mic grid is ALWAYS mounted. It used to be removed
                // while the keyboard was up (`if (!imeOpen)`), but that
                // added/removed a ~200 dp block on every IME transition,
                // which re-laid-out the whole column and — together with
                // `Modifier.imePadding()` — made the keyboard flicker
                // closed-then-open and left the user unable to settle in
                // the composer. `imePadding()` alone now lifts the room
                // above the keyboard.
                MicSeatsGrid(
                    seatOccupants = seatOccupants,
                    lockedSeats = lockedSeats,
                    mutedPubkeys = mutedPubkeys,
                    myPubkey = myPubkey,
                    openMic = openMic,
                    canSpeak = canSpeak,
                    isHost = isHost,
                    isHostOrMod = isHostOrMod,
                    accountViewModel = accountViewModel,
                    onTakeSeat = { seat, unlockIfLocked ->
                        if (isHostOrMod) {
                            RoomParticipantActions
                                .claimSeat(
                                    original = event,
                                    targetPubkey = myPubkey,
                                    seat = seat,
                                    requireOpenMic = false,
                                    isHostOrMod = true,
                                    unlockIfLocked = unlockIfLocked,
                                )?.let { publishRoomTemplate(it) }
                            viewModel.takeMicSeat(seat)
                        } else if (seat !in lockedSeats) {
                            // Guests self-claim an empty unlocked seat directly —
                            // no open-mic/invite gate. Host control stays via
                            // locks + remove/mute.
                            viewModel.takeMicSeat(seat)
                        }
                    },
                    onLeaveSeat = {
                        if (isHostOrMod) {
                            RoomParticipantActions.leaveSeat(event, myPubkey)?.let { publishRoomTemplate(it) }
                        }
                        viewModel.leaveMicSeat()
                        if (ui.broadcast is com.vitorpamplona.amethyst.commons.viewmodels.BroadcastUiState.Broadcasting ||
                            ui.broadcast == com.vitorpamplona.amethyst.commons.viewmodels.BroadcastUiState.Connecting
                        ) {
                            viewModel.stopBroadcast()
                        }
                    },
                    onLockSeat = { seat, locked ->
                        if (isHost) {
                            RoomParticipantActions.setMicLocked(event, seat, locked)?.let { publishRoomTemplate(it) }
                        }
                    },
                    onRemoveFromMic = { pubkey ->
                        if (isHost) {
                            RoomParticipantActions.leaveSeat(event, pubkey)?.let { publishRoomTemplate(it) }
                        }
                    },
                    onViewProfile = { pubkey ->
                        openProfileInMainActivity(
                            context = topBarContext,
                            target = pubkey,
                            accountViewModel = accountViewModel,
                            noAppMessage = stringRes(topBarContext, R.string.nest_no_app_to_open_link),
                        )
                    },
                    onTapSelf = onTapSelf,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                // Transient "X joined the room" banner, pinned right
                // under the mic grid. It self-fades after a few seconds
                // so the stage stays readable in a busy room.
                JoinBanner(
                    notices = notices,
                    accountViewModel = accountViewModel,
                )
                // Recent-gift ticker sits directly below the mic seats
                // (in the normal column flow) rather than pinned to the
                // top of the screen, so the "who sent what" chips read
                // as an extension of the stage instead of an overlay
                // covering the room header.
                RoomGiftTicker(
                    giftsByTarget = giftsByTarget,
                    nameFor = { pk ->
                        LocalCache.getUserIfExists(pk)?.toBestDisplayName() ?: pk.take(8)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                NestChatPanel(
                    event = event,
                    roomNote = roomNote,
                    viewModel = viewModel,
                    accountViewModel = accountViewModel,
                    nestScreenModel = nestChatModel,
                    includeComposer = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            // Gift playback overlay — above the stage and chat, below
            // the sheets/dialogs mounted outside the Scaffold. The
            // full-screen burst is pointer-transparent (it draws only
            // via graphicsLayer) so it never steals taps from the room.
            RoomGiftOverlay(
                giftsByTarget = giftsByTarget,
                nameFor = { pk ->
                    LocalCache.getUserIfExists(pk)?.toBestDisplayName() ?: pk.take(8)
                },
                clientBuilder = accountViewModel.httpClientBuilder,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // Sheets and dialogs render alongside the Scaffold so they
    // cover the room content (including the action bar) when
    // open. ParticipantHostActionsSheet stays outside the Column
    // for the same reason — modal bottom sheets are not affected
    // by parent layout.
    hostMenuTarget?.let { target ->
        ParticipantHostActionsSheet(
            target = target,
            event = event,
            accountViewModel = accountViewModel,
            nestViewModel = viewModel,
            onDismiss = { hostMenuTarget = null },
            catalog = speakerCatalogs[target],
        )
    }

    if (wantsToReact) {
        RoomReactionPopup(
            roomNote = roomNote,
            iconSize = 24.dp,
            accountViewModel = accountViewModel,
            onDismiss = { wantsToReact = false },
            onChangeAmount = { wantsToReact = false },
        )
    }

    if (showAudienceSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showAudienceSheet = false },
            containerColor = Color(0xFF121212),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                if (showHandsTab) {
                    HandRaiseQueueSection(
                        event = event,
                        viewModel = viewModel,
                        accountViewModel = accountViewModel,
                        onLongPressParticipant = onLongPressParticipant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                AudienceGrid(
                    members = participantGrid.audience,
                    accountViewModel = accountViewModel,
                    onLongPressParticipant = onLongPressParticipant,
                    onTapParticipant = onLongPressParticipant,
                    myPubkey = myPubkey,
                    reactionsByPubkey = reactionsByPubkey,
                    zapsByPubkey = zapsByPubkey,
                    modifier =
                        Modifier
                            .heightIn(max = 420.dp)
                            .padding(horizontal = 12.dp),
                )
            }
        }
    }

    if (showHostLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showHostLeaveConfirm = false },
            title = { Text(stringRes(R.string.nest_leave_host_title)) },
            text = { Text(stringRes(R.string.nest_leave_host_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHostLeaveConfirm = false
                        leaveScope.launch {
                            viewModel.leaveMicSeat()
                            publishGonePresence(accountViewModel, event)
                            viewModel.clearChat()
                            viewModel.leave()
                            onLeave()
                        }
                    },
                ) {
                    Text(stringRes(R.string.nest_leave_host_just_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHostLeaveConfirm = false }) {
                    Text(stringRes(R.string.cancel))
                }
            },
        )
    }

    if (showEditSheet) {
        EditNestSheet(
            accountViewModel = accountViewModel,
            event = event,
            onDismiss = { showEditSheet = false },
        )
    }
}

/**
 * Expanded summary strip shown between the TopAppBar and the stage grid
 * when the user taps the title. The LIVE chip and listener count have
 * moved to the stage card's header row, so this strip now carries only
 * the room description.
 */
@Composable
private fun RoomSummaryStrip(summary: String?) {
    if (summary.isNullOrBlank()) return
    Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Broadcast an authoritative "I left" presence so peers drop us from
 * mic seats immediately (host [HostMicSeatAuthority] clears kind-30312).
 */
private suspend fun publishGonePresence(
    accountViewModel: AccountViewModel,
    event: MeetingSpaceEvent,
) {
    runCatching {
        accountViewModel.account.signAndComputeBroadcast(
            com.vitorpamplona.quartz.nip53LiveActivities.presence.MeetingRoomPresenceEvent.build(
                root = event,
                handRaised = false,
                muted = null,
                publishing = false,
                onstage = false,
                micSeat = null,
                left = true,
            ),
        )
    }
}

/**
 * Re-publish the host's [event] with `status="closed"`, preserving every
 * other field verbatim (room name, summary, image, service, endpoint,
 * participants). Returns true on success.
 *
 * Reuses [EditNestViewModel.buildEditTemplate] so the close-on-leave
 * path emits the same shape as the explicit Edit → Close room action.
 * Decoupled from [EditNestViewModel] state so the host's unsaved
 * edits in the Edit sheet (if any) cannot accidentally leak into the
 * close payload.
 */
private suspend fun closeMeetingSpace(
    accountViewModel: AccountViewModel,
    event: MeetingSpaceEvent,
): Boolean {
    val verbatim =
        EditNestViewModel.FormState(
            dTag = event.dTag(),
            roomName = event.room().orEmpty(),
            summary = event.summary().orEmpty(),
            imageUrl = event.image().orEmpty(),
            endpointUrl = event.endpoint().orEmpty(),
            serviceUrl = event.service().orEmpty(),
            isPublishing = false,
            error = null,
        )
    return try {
        val template =
            EditNestViewModel.buildEditTemplate(event, verbatim, StatusTag.STATUS.ENDED)
        accountViewModel.account.signAndComputeBroadcast(template)
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * Build an `nostr:naddr1...` URI for the room and hand it to the
 * system share sheet. Includes the room title in the share text
 * so the receiving app can render a preview without round-tripping
 * the relay.
 */
private fun shareRoomNaddr(
    context: android.content.Context,
    event: MeetingSpaceEvent,
) {
    val aTag = ATag(event.kind, event.pubKey, event.dTag(), null)
    val naddr = aTag.toNAddr()
    val title = event.room().orEmpty()
    val text = if (title.isNotBlank()) "$title — nostr:$naddr" else "nostr:$naddr"
    val intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
    context.startActivity(android.content.Intent.createChooser(intent, null))
}
