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
package com.vitorpamplona.amethyst.commons.defaults

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl

/** REQ limit sent to algo-relay per filter (recommendations only; main feed uses ME relay limits). */
const val ASTRO_ALGO_REQ_LIMIT = 500

/**
 * Temporary UX flag: show a "recommendation" badge next to author names for algo-served notes.
 * Flip to false (or delete call sites) to remove.
 */
const val SHOW_RECOMMENDATION_BADGE = true

object AstroSurface {
    const val POSTS = "posts"
    const val FOR_YOU = "foryou"
}

fun NormalizedRelayUrl.isAstroAlgoRelay(): Boolean = this == Constants.astroAlgo

/** Nostr filter tag on algo-relay REQ: `refresh` rebuilds interest pool; `more` advances batch. */
fun astroAlgoReqTags(
    pullRefresh: Boolean,
    surface: String = AstroSurface.POSTS,
): Map<String, List<String>> = mapOf("astro" to listOf(if (pullRefresh) "refresh" else "more", surface))
