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
package com.vitorpamplona.amethyst.ui.actions

/**
 * Which surface the media composer publishes to. It is the single switch that lets
 * one unified editor produce a feed post, a reel, or a 24h story without the editor
 * itself knowing the difference.
 */
enum class ComposeContentType {
    /** Permanent feed post: kind 20 for images, kind 21/22 for video. */
    FEED,

    /** Reel: NIP-71 vertical video (kind 34236) so it lands in the reels surface. */
    REEL,

    /** 24h ephemeral story (kind 30923). */
    STORY,

    /** Instant: same wire format as a story, but framed for close friends. */
    INSTANT,
    ;

    val isEphemeral: Boolean
        get() = this == STORY || this == INSTANT
}
