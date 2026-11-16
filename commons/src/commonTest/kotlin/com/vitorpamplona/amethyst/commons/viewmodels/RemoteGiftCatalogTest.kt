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
    fun positionIsMappedAndClamped() {
        val sku =
            RemoteGiftCatalog(
                gifts = listOf(RemoteGiftSku(id = "a", tier = 1, price = 1, posX = 0.25f, posY = -0.5f)),
            ).toSkus(base).single()
        assertEquals(0.25f, sku.offsetX)
        assertEquals(-0.5f, sku.offsetY)

        // A wild value must not push the clip off the screen entirely.
        val wild =
            RemoteGiftCatalog(
                gifts = listOf(RemoteGiftSku(id = "b", tier = 1, price = 1, posX = 9f, posY = -9f)),
            ).toSkus(base).single()
        assertEquals(0.5f, wild.offsetX)
        assertEquals(-0.5f, wild.offsetY)

        // Absent position keeps the gift centred.
        val centred =
            RemoteGiftCatalog(
                gifts = listOf(RemoteGiftSku(id = "c", tier = 1, price = 1)),
            ).toSkus(base).single()
        assertNull(centred.offsetX)
        assertNull(centred.offsetY)
    }

    @Test
    fun malformedJsonIsNullNotACrash() {
        assertNull(RemoteGiftCatalog.parseOrNull("not json at all"))
        assertNull(RemoteGiftCatalog.parseOrNull(""))
    }

    @Test
    fun resolvesTheExactPayloadTheDashboardServes() {
        // Verbatim output of the running dashboard's /gifts/catalog.json,
        // so a change to what the server actually emits is caught here
        // rather than only in a hand-written fixture.
        val served =
            """
            {"version":9,"gifts":[
              {"id":"mmi","emoji":"🦁","labelAr":"اسود","labelEn":"Ac","tier":3,"price":1000,
               "svga":"clips/a830a58ab436a3c8.svga","icon":"icons/9119efbaf15eb1e1.png",
               "sound":"sounds/040a77e174807d1d.wav","scale":0.9,"posX":0,"posY":0},
              {"id":"lionlive","emoji":"🦁","labelAr":"الأسد الحي","labelEn":"Live Lion","tier":4,
               "price":9999,"svga":"clips/9e1ab95038491291.svga","icon":"icons/df8b2428d0b2043c.png",
               "sound":"sounds/42849ced3c3d68cd.wav","scale":0.9,"posX":0.1,"posY":-0.15}
            ]}
            """.trimIndent()
        val catalog = RemoteGiftCatalog.parseOrNull(served)!!
        val skus = catalog.toSkus("https://host.example/gifts/catalog.json")
        assertEquals(listOf("mmi", "lionlive"), skus.map { it.id })
        val lion = skus.single { it.id == "lionlive" }
        assertEquals("https://host.example/gifts/clips/9e1ab95038491291.svga", lion.svga)
        assertEquals("https://host.example/gifts/icons/df8b2428d0b2043c.png", lion.icon)
        assertEquals("https://host.example/gifts/sounds/42849ced3c3d68cd.wav", lion.sound)
        assertEquals(4, lion.tier)
        assertEquals(9999, lion.value)
        assertEquals(0.1f, lion.offsetX)
        assertEquals(-0.15f, lion.offsetY)
        assertTrue(lion.remote)
    }
}
