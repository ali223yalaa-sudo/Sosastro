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

/**
 * Encrypted NIP-17 kind-14 rumors that are not chat text. They travel the same
 * gift-wrap path as DMs (signed by the sender, sealed to the peer) so neither
 * receipts nor theme changes can be spoofed or read by a third party.
 *
 * Kept out of the visible thread by [Chatroom] / [ChatroomFeedFilter].
 */
object ChatControlMessage {
    const val READ_PREFIX = "astro:read:"
    const val DELIVERED_PREFIX = "astro:delivered:"
    const val THEME_PREFIX = "astro:theme:"

    fun isControl(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        return content.startsWith(READ_PREFIX) ||
            content.startsWith(DELIVERED_PREFIX) ||
            content.startsWith(THEME_PREFIX)
    }

    fun encodeRead(until: Long): String = READ_PREFIX + until

    fun encodeDelivered(until: Long): String = DELIVERED_PREFIX + until

    fun encodeTheme(themeId: String): String = THEME_PREFIX + themeId

    fun parseReadUntil(content: String): Long? {
        if (!content.startsWith(READ_PREFIX)) return null
        return content.removePrefix(READ_PREFIX).toLongOrNull()?.takeIf { it > 0L }
    }

    fun parseDeliveredUntil(content: String): Long? {
        if (!content.startsWith(DELIVERED_PREFIX)) return null
        return content.removePrefix(DELIVERED_PREFIX).toLongOrNull()?.takeIf { it > 0L }
    }

    fun parseThemeId(content: String): String? {
        if (!content.startsWith(THEME_PREFIX)) return null
        val id = content.removePrefix(THEME_PREFIX)
        return id.takeIf { it.isNotBlank() && it.length <= 32 && id.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
    }
}
