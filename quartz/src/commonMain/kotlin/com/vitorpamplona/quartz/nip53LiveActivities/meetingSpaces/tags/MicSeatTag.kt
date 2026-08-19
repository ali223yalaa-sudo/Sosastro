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
package com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * Fixed mic seat on a kind-30312 room: `["mic", "<0-9>", "<pubkey>"]`.
 *
 * Visible to every client that reads the replaceable room event so
 * seat claims are shared and conflicting ascents are rejected when
 * the seat is already taken.
 */
class MicSeatTag {
    companion object {
        const val TAG_NAME = "mic"
        const val SEAT_COUNT = 10

        fun parse(tag: Array<String>): Pair<Int, HexKey>? {
            ensure(tag.has(2)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            val seat = tag[1].toIntOrNull() ?: return null
            ensure(seat in 0 until SEAT_COUNT) { return null }
            ensure(tag[2].length == 64) { return null }
            return seat to tag[2]
        }

        fun assemble(
            seat: Int,
            pubKey: HexKey,
        ): Array<String> {
            require(seat in 0 until SEAT_COUNT)
            return arrayOf(TAG_NAME, seat.toString(), pubKey)
        }
    }
}
