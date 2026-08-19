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
package com.vitorpamplona.quartz.experimental.ephemeralStory

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MimeTypeTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.UrlTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Kind 30923 — ephemeral media story (24h), compatible with Flare / nip-30923.
 *
 * Tags: `d` (unique id), `url`, `m` (mime), optional `x` (sha256), `expiration` (NIP-40),
 * optional `alt`. Content is an optional caption.
 */
@Immutable
class EphemeralStoryEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    SearchableEvent {
    override fun indexableContent() = listOfNotNull(content.ifBlank { null }, url()).joinToString("\n")

    fun url() = tags.firstNotNullOfOrNull(UrlTag::parse)

    fun mimeType() = tags.firstNotNullOfOrNull(MimeTypeTag::parse)

    fun hash() = tags.firstNotNullOfOrNull(HashSha256Tag::parse)

    fun isVideo(): Boolean {
        val mime = mimeType()
        if (mime?.startsWith("video/") == true) return true
        val mediaUrl = url() ?: return false
        return mediaUrl.contains(".mp4", ignoreCase = true) ||
            mediaUrl.contains(".webm", ignoreCase = true) ||
            mediaUrl.contains(".mov", ignoreCase = true)
    }

    fun isImage(): Boolean {
        val mime = mimeType()
        if (mime?.startsWith("image/") == true) return true
        return !isVideo() && url() != null
    }

    fun stillActive(now: Long = TimeUtils.now()): Boolean {
        if (isExpired()) return false
        if (createdAt < now - EXPIRY_SECONDS) return false
        return url() != null
    }

    companion object {
        const val KIND = 30923
        const val EXPIRY_SECONDS = TimeUtils.ONE_DAY

        fun build(
            url: String,
            mimeType: String?,
            hash: String?,
            caption: String,
            dTag: String,
            expirationAt: Long = TimeUtils.oneDayAhead(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<EphemeralStoryEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, caption, createdAt) {
            dTag(dTag)
            add(UrlTag.assemble(url))
            mimeType?.let { add(MimeTypeTag.assemble(it)) }
            hash?.let { add(HashSha256Tag.assemble(it)) }
            expiration(expirationAt)
            caption.ifBlank { null }?.let { alt(it) }
            initializer()
        }
    }
}
