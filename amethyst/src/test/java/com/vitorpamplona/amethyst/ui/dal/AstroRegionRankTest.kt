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
package com.vitorpamplona.amethyst.ui.dal

import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHash
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip71Video.VideoVerticalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Astro auto-ranking must promote Iraqi content first, then the wider Arab world,
 * then everything else — derived from what the note actually carries (region tags,
 * geohash, or Arabic script) with no manual filters.
 */
class AstroRegionRankTest {
    @Test
    fun `iraqi hashtag ranks as home tier`() {
        assertEquals(AstroRegionRank.TIER_HOME, AstroRegionRank.tier(noteWithHashtags("iraq", "video")))
        assertEquals(AstroRegionRank.TIER_HOME, AstroRegionRank.tier(noteWithHashtags("بغداد")))
    }

    @Test
    fun `other arab hashtag ranks as arab tier`() {
        assertEquals(AstroRegionRank.TIER_ARAB, AstroRegionRank.tier(noteWithHashtags("egypt")))
        assertEquals(AstroRegionRank.TIER_ARAB, AstroRegionRank.tier(noteWithHashtags("السعودية")))
    }

    @Test
    fun `iraqi hashtag wins over another arab hashtag`() {
        assertEquals(AstroRegionRank.TIER_HOME, AstroRegionRank.tier(noteWithHashtags("dubai", "mosul")))
    }

    @Test
    fun `geohash inside iraq ranks as home tier`() {
        val baghdad = GeoHash.encode(33.3152, 44.3661, 9).toString()
        assertEquals(AstroRegionRank.TIER_HOME, AstroRegionRank.tier(noteWithGeohash(baghdad)))
    }

    @Test
    fun `geohash inside another arab country ranks as arab tier`() {
        val cairo = GeoHash.encode(30.0444, 31.2357, 9).toString()
        assertEquals(AstroRegionRank.TIER_ARAB, AstroRegionRank.tier(noteWithGeohash(cairo)))
    }

    @Test
    fun `geohash outside the arab world ranks as global tier`() {
        val london = GeoHash.encode(51.5074, -0.1278, 9).toString()
        assertEquals(AstroRegionRank.TIER_GLOBAL, AstroRegionRank.tier(noteWithGeohash(london)))
    }

    @Test
    fun `arabic script content ranks as arab tier`() {
        assertEquals(AstroRegionRank.TIER_ARAB, AstroRegionRank.tier(noteWithContent("مرحبا بالعالم")))
    }

    @Test
    fun `non-arabic text without tags ranks as global tier`() {
        assertEquals(AstroRegionRank.TIER_GLOBAL, AstroRegionRank.tier(noteWithContent("hello world")))
    }

    @Test
    fun `video with an arabic title but empty content ranks as arab tier`() {
        // NIP-71 videos keep the caption in content but the title in a `title` tag.
        // Ranking must look at both, otherwise Arabic reels fall to the global tier.
        val video =
            VideoVerticalEvent(
                "vid",
                "pub",
                0L,
                arrayOf(arrayOf("title", "مقطع عراقي جميل"), arrayOf("t", "بغداد")),
                "",
                "sig",
            )
        val note = Note("vid").apply { this.event = video }
        assertEquals(AstroRegionRank.TIER_HOME, AstroRegionRank.tier(note))
    }

    @Test
    fun `note without an event ranks as global tier`() {
        assertEquals(AstroRegionRank.TIER_GLOBAL, AstroRegionRank.tier(Note("no-event")))
    }

    @Test
    fun `astroRegionRanked orders iraq then arab then global`() {
        val global = noteWithHashtags("london", id = "g")
        val arab = noteWithHashtags("egypt", id = "a")
        val iraqi = noteWithHashtags("iraq", id = "i")

        val ranked = listOf(global, arab, iraqi).astroRegionRanked()

        assertEquals(listOf("i", "a", "g"), ranked.map { it.idHex })
    }

    @Test
    fun `astroRegionRanked is stable within a tier`() {
        val first = noteWithHashtags("iraq", id = "i1")
        val second = noteWithHashtags("iraq", id = "i2")
        val third = noteWithHashtags("iraq", id = "i3")

        val ranked = listOf(first, second, third).astroRegionRanked()

        assertEquals(listOf("i1", "i2", "i3"), ranked.map { it.idHex })
    }

    @Test
    fun `isArabicScript detects arabic and rejects latin`() {
        assertTrue(AstroRegionRank.isArabicScript("بغداد"))
        assertFalse(AstroRegionRank.isArabicScript("baghdad"))
    }

    @Test
    fun `isArabCountry recognises arab states case-insensitively`() {
        assertTrue(AstroRegionRank.isArabCountry("iq"))
        assertTrue(AstroRegionRank.isArabCountry("SA"))
        assertFalse(AstroRegionRank.isArabCountry("us"))
        assertFalse(AstroRegionRank.isArabCountry(null))
    }

    // ---- helpers ----

    private fun noteWithHashtags(
        vararg hashtags: String,
        id: String = "test-note",
    ): Note = noteWithTags(hashtags.map { arrayOf("t", it) }.toTypedArray(), id = id)

    private fun noteWithGeohash(
        geohash: String,
        id: String = "test-note",
    ): Note = noteWithTags(arrayOf(arrayOf("g", geohash)), id = id)

    private fun noteWithContent(
        content: String,
        id: String = "test-note",
    ): Note = noteWithTags(emptyArray(), content = content, id = id)

    private fun noteWithTags(
        tags: Array<Array<String>>,
        content: String = "",
        id: String = "test-note",
    ): Note {
        val event = TextNoteEvent(id, "pub", 0L, tags, content, "sig")
        return Note(id).apply { this.event = event }
    }
}
