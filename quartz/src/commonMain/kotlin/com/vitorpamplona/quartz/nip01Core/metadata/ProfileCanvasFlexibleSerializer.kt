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

import com.vitorpamplona.quartz.nip01Core.core.JsonMapper
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Accepts either an escaped JSON string or a nested JSON object for [profile_canvas].
 * Always serializes as a nested object.
 */
object ProfileCanvasFlexibleSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ProfileCanvasFlexible", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString().ifBlank { null }
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.contentOrNull?.trim()?.ifBlank { null }
            is JsonObject ->
                try {
                    JsonMapper.jsonInstance.encodeToString(
                        JsonObject.serializer(),
                        element,
                    )
                } catch (_: Exception) {
                    element.toString()
                }.ifBlank { null }
            else -> null
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: String?,
    ) {
        val jsonEncoder = encoder as? JsonEncoder
        if (value.isNullOrBlank()) {
            if (jsonEncoder != null) {
                jsonEncoder.encodeJsonElement(JsonNull)
            } else {
                encoder.encodeNull()
            }
            return
        }
        if (jsonEncoder != null) {
            val element =
                try {
                    JsonMapper.jsonInstance.parseToJsonElement(value.trim())
                } catch (_: Exception) {
                    JsonPrimitive(value.trim())
                }
            jsonEncoder.encodeJsonElement(element)
        } else {
            encoder.encodeString(value)
        }
    }
}
