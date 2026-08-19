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
package com.vitorpamplona.quartz.nip01Core.metadata

/**
 * Ordered profile header blocks. Reorder only — blocks are never removed from the
 * saved list so viewers always know the owner's preferred order. Empty blocks are
 * simply skipped at render time.
 */
enum class ProfileLayoutBlock(
    val id: String,
) {
    DISPLAY_NAME("display_name"),
    PROFESSION("profession"),
    USERNAME("username"),
    NPUB("npub"),
    NPROFILE("nprofile"),
    LAST_SEEN("last_seen"),
    NIP05("nip05"),
    WEBSITE("website"),
    IDENTITIES("identities"),
    PAYMENT("payment"),
    BADGES("badges"),
    ABOUT("about"),
    APP_RECOMMENDATIONS("app_recommendations"),
    ;

    companion object {
        val DEFAULT_ORDER: List<ProfileLayoutBlock> = entries.toList()

        fun parse(raw: String?): List<ProfileLayoutBlock> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val parsed =
                raw
                    .split(',')
                    .map { it.trim() }
                    .mapNotNull { token -> entries.firstOrNull { it.id == token } }
            // Keep every known block: append any missing ones in default order (no delete).
            return (parsed + DEFAULT_ORDER.filter { it !in parsed }).distinct()
        }

        fun serialize(blocks: List<ProfileLayoutBlock>): String =
            blocks
                .distinct()
                .let { ordered ->
                    ordered + DEFAULT_ORDER.filter { it !in ordered }
                }.joinToString(",") { it.id }
    }
}
