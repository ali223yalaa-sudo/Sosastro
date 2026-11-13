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
package com.vitorpamplona.amethyst.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ghost-presence fix: a terminal kind-10312 leave that shares a
 * second with the peer's last heartbeat must still be accepted, or the peer's
 * avatar lingers until the 2-minute eviction fallback.
 */
class RoomPresenceReplaceableAcceptTest {
    @Test
    fun strictlyNewerReplaceableWins() {
        assertTrue(replaceableUpdateAccepted(incomingCreatedAt = 101L, storedCreatedAt = 100L, incomingIsTerminalRoomLeave = false))
    }

    @Test
    fun strictlyOlderReplaceableLoses() {
        assertFalse(replaceableUpdateAccepted(incomingCreatedAt = 99L, storedCreatedAt = 100L, incomingIsTerminalRoomLeave = false))
    }

    @Test
    fun sameSecondNonLeaveReplaceableLoses() {
        assertFalse(replaceableUpdateAccepted(incomingCreatedAt = 100L, storedCreatedAt = 100L, incomingIsTerminalRoomLeave = false))
    }

    @Test
    fun sameSecondTerminalLeaveWins() {
        assertTrue(replaceableUpdateAccepted(incomingCreatedAt = 100L, storedCreatedAt = 100L, incomingIsTerminalRoomLeave = true))
    }

    @Test
    fun olderTerminalLeaveStillLoses() {
        assertFalse(replaceableUpdateAccepted(incomingCreatedAt = 99L, storedCreatedAt = 100L, incomingIsTerminalRoomLeave = true))
    }

    @Test
    fun firstEventIsAccepted() {
        assertTrue(replaceableUpdateAccepted(incomingCreatedAt = 1L, storedCreatedAt = null, incomingIsTerminalRoomLeave = false))
    }
}
