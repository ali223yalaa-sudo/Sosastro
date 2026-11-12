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

import com.vitorpamplona.amethyst.model.CallTurnServer
import com.vitorpamplona.quartz.utils.Log
import org.webrtc.PeerConnection

object IceServerConfig {
    private const val TAG = "IceServerConfig"

    val defaultStunServers =
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        )

    private const val METERED_TURN_USERNAME = "9fcc657d6a0265a8d28ffb0f"
    private const val METERED_TURN_CREDENTIAL = "LirDeJ1gwYg/AsNS"

    /**
     * Built-in TURN fallback, used only when the dynamic fetch in
     * [MeteredTurnProvider] fails (offline at call start, Metered outage).
     * These point at Metered's shared relay with the app's static credential;
     * port 80 and 443 both carry `?transport=tcp` variants because restrictive
     * mobile/corporate networks routinely block outbound UDP.
     */
    val defaultTurnServers =
        listOf(
            PeerConnection.IceServer
                .builder("turn:standard.relay.metered.ca:80")
                .setUsername(METERED_TURN_USERNAME)
                .setPassword(METERED_TURN_CREDENTIAL)
                .createIceServer(),
            PeerConnection.IceServer
                .builder("turn:standard.relay.metered.ca:80?transport=tcp")
                .setUsername(METERED_TURN_USERNAME)
                .setPassword(METERED_TURN_CREDENTIAL)
                .createIceServer(),
            PeerConnection.IceServer
                .builder("turn:standard.relay.metered.ca:443")
                .setUsername(METERED_TURN_USERNAME)
                .setPassword(METERED_TURN_CREDENTIAL)
                .createIceServer(),
            PeerConnection.IceServer
                .builder("turns:standard.relay.metered.ca:443?transport=tcp")
                .setUsername(METERED_TURN_USERNAME)
                .setPassword(METERED_TURN_CREDENTIAL)
                .createIceServer(),
        )

    /**
     * Resolves the ICE server list for a call, preferring, in order:
     *
     *  1. user-configured TURN servers (Settings → Calls),
     *  2. freshly fetched Metered credentials ([MeteredTurnProvider]),
     *  3. the static [defaultTurnServers] fallback.
     *
     * STUN servers are always included.  Never throws: a failed dynamic fetch
     * degrades to the static fallback so a call can still attempt to connect.
     */
    suspend fun buildIceServers(userTurnServers: List<CallTurnServer> = emptyList()): List<PeerConnection.IceServer> {
        val turnServers =
            when {
                userTurnServers.isNotEmpty() ->
                    userTurnServers.map { turn ->
                        PeerConnection.IceServer
                            .builder(turn.url)
                            .setUsername(turn.username)
                            .setPassword(turn.credential)
                            .createIceServer()
                    }

                else -> {
                    val fetched = MeteredTurnProvider.fetch()
                    if (fetched.isNullOrEmpty()) {
                        Log.w(TAG) {
                            "Dynamic TURN fetch unavailable — using static Metered fallback."
                        }
                        defaultTurnServers
                    } else {
                        fetched
                    }
                }
            }
        return defaultStunServers + turnServers
    }
}
