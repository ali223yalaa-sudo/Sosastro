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
package com.vitorpamplona.quartz.nipB7Blossom

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlossomAuthorizationEventTest {
    private fun event(content: String) =
        BlossomAuthorizationEvent(
            id = "0".repeat(64),
            pubKey = "1".repeat(64),
            createdAt = 1_700_000_000,
            tags = arrayOf(arrayOf("t", "upload")),
            content = content,
            sig = "2".repeat(128),
        )

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun rawTokenIsUnpaddedBase64Url() {
        // BUD-01/BUD-02 require base64url without padding. A padded or standard-alphabet
        // token is rejected by strict servers, so guard the exact encoding here.
        val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        var differsFromStandard = false

        for (i in 0 until 64) {
            val auth = event("Astro upload probe #$i")
            val json = auth.toJson().encodeToByteArray()

            assertEquals(urlSafe.encode(json), auth.rawToken())
            if (Base64.encode(json) != auth.rawToken()) {
                differsFromStandard = true
            }
        }

        assertTrue(differsFromStandard, "test inputs never exercised the +/ or padding difference")
    }
}
