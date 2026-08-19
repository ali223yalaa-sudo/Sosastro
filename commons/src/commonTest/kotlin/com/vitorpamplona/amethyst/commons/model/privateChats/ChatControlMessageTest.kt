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
package com.vitorpamplona.amethyst.commons.model.privateChats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatControlMessageTest {
    @Test
    fun ordinaryChatIsNotControl() {
        assertFalse(ChatControlMessage.isControl("hello"))
        assertFalse(ChatControlMessage.isControl(null))
        assertFalse(ChatControlMessage.isControl(""))
    }

    @Test
    fun readReceiptRoundTrip() {
        val encoded = ChatControlMessage.encodeRead(1_700_000_000L)
        assertTrue(ChatControlMessage.isControl(encoded))
        assertEquals(1_700_000_000L, ChatControlMessage.parseReadUntil(encoded))
        assertNull(ChatControlMessage.parseThemeId(encoded))
    }

    @Test
    fun themeRoundTripRejectsJunk() {
        val encoded = ChatControlMessage.encodeTheme("ocean")
        assertEquals("ocean", ChatControlMessage.parseThemeId(encoded))
        assertNull(ChatControlMessage.parseThemeId(ChatControlMessage.encodeTheme("not a theme!!")))
        assertNull(ChatControlMessage.parseReadUntil(encoded))
    }

    @Test
    fun deliveredReceiptRoundTrip() {
        val encoded = ChatControlMessage.encodeDelivered(1_700_000_001L)
        assertTrue(ChatControlMessage.isControl(encoded))
        assertEquals(1_700_000_001L, ChatControlMessage.parseDeliveredUntil(encoded))
        assertNull(ChatControlMessage.parseReadUntil(encoded))
    }
}
