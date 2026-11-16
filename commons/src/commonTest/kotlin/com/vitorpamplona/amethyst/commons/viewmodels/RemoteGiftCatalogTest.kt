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
package com.vitorpamplona.amethyst.commons.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteGiftCatalogTest {
    private val base = "https://gifts.example.com/gifts/catalog.json"

    @Test
    fun parsesTheDashboardPayloadShape() {
        val json =
            """
            {"version":3,"gifts":[
              {"id":"mylion","emoji":"🦁","labelAr":"أسد","labelEn":"Lion",
               "tier":4,"price":5000,"svga":"clips/a1b2.svga","icon":"icons/c3d4.png",
               "sound":"sounds/e5f6.wav","scale":0.9}
            ]}
            """.trimIndent()
        val catalog = RemoteGiftCatalog.parseOrNull(json)!!
        assertEquals(3, catalog.version)
        val sku = catalog.toSkus(base).single()
        assertEquals("mylion", sku.id)
        assertEquals(4, sku.tier)
        assertEquals(5000, sku.value)
        // Relative paths are resolved against the catalog's own directory.
        assertEquals("https://gifts.example.com/gifts/clips/a1b2.svga", sku.svga)
        assertEquals("https://gifts.example.com/gifts/icons/c3d4.png", sku.icon)
        assertEquals("https://gifts.example.com/gifts/sounds/e5f6.wav", sku.sound)
        assertEquals(0.9f, sku.scale)
        assertTrue(sku.remote)
    }

    @Test
    fun keepsAbsoluteUrlsAsAuthored() {
        val sku =
            RemoteGiftCatalog(
                gifts =
                    listOf(
                        RemoteGiftSku(
                            id = "abs",
                            tier = 2,
                            price = 10,
                            svga = "https://cdn.example.com/x.svga",
                        ),
                    ),
            ).toSkus(base).single()
        assertEquals("https://cdn.example.com/x.svga", sku.svga)
    }

    @Test
    fun rejectsUnsafeAssetSchemes() {
        val sku =
            RemoteGiftCatalog(
                gifts =
                    listOf(
                        RemoteGiftSku(
                            id = "evil",
                            tier = 1,
                            price = 1,
                            svga = "javascript:alert(1)",
                            icon = "file:///etc/passwd",
                        ),
                    ),
            ).toSkus(base).single()
        assertNull(sku.svga)
        assertNull(sku.icon)
    }

    @Test
    fun dropsMalformedRowsButKeepsGoodOnes() {
        val catalog =
            RemoteGiftCatalog(
                gifts =
                    listOf(
                        RemoteGiftSku(id = "", tier = 1, price = 1),
                        RemoteGiftSku(id = "ok", tier = 2, price = 5),
                        RemoteGiftSku(id = "badTier", tier = 9, price = 5),
                        RemoteGiftSku(id = "badPrice", tier = 1, price = -1),
                    ),
            )
        val ids = catalog.toSkus(base).map { it.id }
        assertEquals(listOf("ok"), ids)
    }

    @Test
    fun scaleIsClampedToTheRenderableRange() {
        val low = RemoteGiftCatalog(gifts = listOf(RemoteGiftSku(id = "a", tier = 1, price = 1, scale = 0.01f)))
        val high = RemoteGiftCatalog(gifts = listOf(RemoteGiftSku(id = "b", tier = 1, price = 1, scale = 9f)))
        assertEquals(0.4f, low.toSkus(base).single().scale)
        assertEquals(1.2f, high.toSkus(base).single().scale)
    }

    @Test
    fun malformedJsonIsNullNotACrash() {
        assertNull(RemoteGiftCatalog.parseOrNull("not json at all"))
        assertNull(RemoteGiftCatalog.parseOrNull(""))
    }
}
