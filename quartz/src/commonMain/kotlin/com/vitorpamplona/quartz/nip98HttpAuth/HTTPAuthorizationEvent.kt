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
package com.vitorpamplona.quartz.nip98HttpAuth

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip98HttpAuth.tags.MethodTag
import com.vitorpamplona.quartz.nip98HttpAuth.tags.PayloadHashTag
import com.vitorpamplona.quartz.nip98HttpAuth.tags.UrlTag
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Immutable
class HTTPAuthorizationEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {
    fun method() = tags.firstNotNullOfOrNull(MethodTag::parse)

    fun payloadHash() = tags.firstNotNullOfOrNull(PayloadHashTag::parse)

    fun url() = tags.firstNotNullOfOrNull(UrlTag::parse)

    /**
     * Base64url (unpadded) of this event's JSON, as carried in the `Authorization` header.
     * NIP-98 / BUD-01 specify base64url without padding; strict servers reject a padded or
     * standard-alphabet token as "invalid authorization token".
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun rawToken() = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(toJson().encodeToByteArray())

    fun toAuthToken() = "Nostr ${rawToken()}"

    companion object {
        const val KIND = 27235

        fun build(
            url: String,
            method: String,
            file: ByteArray? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<HTTPAuthorizationEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            url(url)
            method(method)
            file?.let { payload(it) }
            initializer()
        }
    }
}
