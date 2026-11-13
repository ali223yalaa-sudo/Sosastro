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
package com.vitorpamplona.amethyst.service.call

import android.content.Context
import android.content.Intent
import com.vitorpamplona.amethyst.commons.nipACWebRtcCalls.CallManager
import com.vitorpamplona.amethyst.commons.nipACWebRtcCalls.CallState
import com.vitorpamplona.amethyst.service.call.notification.CallNotifier
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "HeadlessCallCoordinator"

/**
 * Keeps an incoming/outgoing call audible and protected while no call UI is
 * mounted — cold start from a notification, app swiped from Recents, or the
 * screen off with only the always-on service alive.
 *
 * The per-call [com.vitorpamplona.amethyst.ui.call.session.CallSession] owns
 * ringing and the foreground service once [com.vitorpamplona.amethyst.ui.call.CallActivity]
 * is on screen. This coordinator only acts as a fallback: it rings and raises
 * the foreground service when the state enters [CallState.IncomingCall] /
 * [CallState.Offering] and no UI is showing, and tears both down on any
 * transition out of the call.
 */
object HeadlessCallCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var audioManager: CallAudioManager? = null
    private var ringing = false
    private var boundManager: CallManager? = null

    /**
     * (Re)binds to [manager]'s state. Idempotent per manager: a repeat call
     * for the same instance is a no-op, an account switch rebinds cleanly.
     */
    fun watch(
        context: Context,
        manager: CallManager,
    ) {
        if (boundManager === manager) return
        job?.cancel()
        stopRinging()
        boundManager = manager
        val appContext = context.applicationContext
        val audio = audioManager ?: CallAudioManager(appContext).also { audioManager = it }

        job =
            scope.launch {
                combine(manager.state, CallSessionBridge.isCallUiMounted) { state, uiMounted -> state to uiMounted }
                    .collectLatest { (state, uiMounted) ->
                        when (state) {
                            is CallState.IncomingCall, is CallState.Offering -> {
                                // The call UI, when mounted, owns ringing + the
                                // FGS. Use the explicit UI flag, not the
                                // ViewModel: a cold-started CallActivity builds
                                // its own headless ViewModel, so accountViewModel
                                // would still be null and we would double-ring.
                                if (uiMounted) {
                                    // UI took over — drop our fallback ring.
                                    stopRinging()
                                    return@collectLatest
                                }

                                if (!ringing) {
                                    ringing = true
                                    audio.startRinging()
                                }
                                if (state is CallState.IncomingCall) {
                                    CallNotifier.showIncomingCall(state.callerPubKey, appContext)
                                }
                                startForegroundService(appContext, state)
                            }

                            else -> stopRinging()
                        }
                    }
            }
    }

    private fun stopRinging() {
        if (!ringing) return
        ringing = false
        audioManager?.stopRinging()
    }

    private fun startForegroundService(
        context: Context,
        state: CallState,
    ) {
        try {
            val isVideo =
                when (state) {
                    is CallState.IncomingCall -> state.callType
                    is CallState.Offering -> state.callType
                    else -> null
                } == com.vitorpamplona.quartz.nipACWebRtcCalls.tags.CallType.VIDEO

            val intent =
                Intent(context, CallForegroundService::class.java).apply {
                    action = CallForegroundService.ACTION_START
                    putExtra(CallForegroundService.EXTRA_IS_RINGING, true)
                    putExtra(CallForegroundService.EXTRA_IS_VIDEO, isVideo)
                }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            // Android 12+ blocks startForegroundService from the background
            // unless an exemption applies; the full-screen notification still
            // rings, so this is a best-effort optimization.
            Log.w(TAG) { "Headless call foreground service unavailable: ${e.message}" }
        }
    }
}
