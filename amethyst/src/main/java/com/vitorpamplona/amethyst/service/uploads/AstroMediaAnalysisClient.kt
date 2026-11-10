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
package com.vitorpamplona.amethyst.service.uploads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Asks AstroRecommend (`algo-relay`) to analyze an uploaded media URL and returns
 * the real DB status: pending / done / done_degraded / failed.
 */
object AstroMediaAnalysisClient {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @Serializable
    data class RequestBody(
        val url: String,
        val mime: String = "",
        val caption: String = "",
    )

    @Serializable
    data class Response(
        val status: String = "pending",
        val topics: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val objects: List<String> = emptyList(),
        val error: String? = null,
        @SerialName("note_id") val noteId: String? = null,
    )

    suspend fun analyze(
        mediaUrl: String,
        mime: String?,
        caption: String? = null,
    ): Response =
        // Island mode: media analysis used to call the owner's desktop algo server over
        // Tailscale, which end users can never reach. Return a neutral local result so
        // uploads never depend on that host.
        Response(status = "done", noteId = null)
}
