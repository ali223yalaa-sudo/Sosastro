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
import com.vitorpamplona.quartz.nip01Core.tags.geohash.getGeoHash
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags

/**
 * Astro: Instagram-style regional ranking, done entirely client-side so it works
 * without redeploying the algo-relay.
 *
 * Priority order is Iraq → other Arab countries → rest of the world, derived from
 * what each note actually carries (geohash tag, region/hashtag tags, Arabic script)
 * plus the signed-in user's own language and country.
 *
 * The ordering is applied as a *stable* re-tier on top of whatever order the feed
 * already computed (chronology or the algo-relay interest ranking), so it only ever
 * promotes local content — it never scrambles the within-tier order.
 */
object AstroRegionRank {
    const val TIER_HOME = 0
    const val TIER_ARAB = 1
    const val TIER_GLOBAL = 2

    private data class BBox(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
    ) {
        fun contains(
            lat: Double,
            lon: Double,
        ) = lat in minLat..maxLat && lon in minLon..maxLon
    }

    private val IRAQ = BBox(29.0, 37.4, 38.8, 48.6)

    // Coarse bounding boxes for the Arab world (North Africa + Middle East + Arabia).
    private val ARAB_COUNTRIES =
        listOf(
            BBox(16.0, 32.2, 34.5, 55.7), // Saudi Arabia
            BBox(12.1, 19.0, 42.5, 54.5), // Yemen
            BBox(16.6, 26.4, 52.0, 59.9), // Oman
            BBox(22.6, 26.1, 51.5, 56.4), // UAE
            BBox(24.4, 26.2, 50.7, 51.7), // Qatar
            BBox(25.5, 26.3, 50.3, 50.8), // Bahrain
            BBox(28.5, 30.1, 46.5, 48.4), // Kuwait
            BBox(29.2, 33.4, 34.9, 39.3), // Jordan
            BBox(33.0, 34.7, 35.1, 36.6), // Lebanon
            BBox(32.3, 37.3, 35.7, 42.4), // Syria
            BBox(31.2, 32.6, 34.2, 35.6), // Palestine
            BBox(22.0, 31.7, 24.7, 36.9), // Egypt
            BBox(19.5, 33.2, 9.3, 25.2), // Libya
            BBox(30.2, 37.6, 7.5, 11.6), // Tunisia
            BBox(18.9, 37.1, -8.7, 12.0), // Algeria
            BBox(21.0, 36.0, -17.1, -1.0), // Morocco
            BBox(20.7, 27.7, -17.1, -8.7), // Western Sahara
            BBox(14.7, 27.3, -17.1, -4.8), // Mauritania
            BBox(8.7, 22.2, 21.8, 38.6), // Sudan
            BBox(-1.7, 12.0, 40.9, 51.4), // Somalia
            BBox(10.9, 12.7, 41.7, 43.4), // Djibouti
        )

    private val IRAQ_TAGS =
        setOf(
            "iraq",
            "iraqi",
            "baghdad",
            "basra",
            "mosul",
            "erbil",
            "kurdistan",
            "kirkuk",
            "najaf",
            "karbala",
            "kufa",
            "sulaymaniyah",
            "duhok",
            "tikrit",
            "العراق",
            "العراقي",
            "بغداد",
            "البصرة",
            "الموصل",
            "اربيل",
            "أربيل",
            "كردستان",
            "كركوك",
            "النجف",
            "كربلاء",
            "الكوفة",
            "السليمانية",
            "دهوك",
            "تكريت",
        )

    private val ARAB_TAGS =
        setOf(
            "arab",
            "arabic",
            "middleeast",
            "middle-east",
            "mena",
            "gulf",
            "khaleeji",
            "saudi",
            "saudiarabia",
            "egypt",
            "emirates",
            "dubai",
            "qatar",
            "kuwait",
            "bahrain",
            "oman",
            "yemen",
            "jordan",
            "lebanon",
            "syria",
            "palestine",
            "libya",
            "tunisia",
            "algeria",
            "morocco",
            "sudan",
            "somalia",
            "mauritania",
            "العرب",
            "العربية",
            "الشرق الأوسط",
            "الشرقالأوسط",
            "الخليج",
            "السعودية",
            "مصر",
            "الإمارات",
            "قطر",
            "الكويت",
            "البحرين",
            "عمان",
            "اليمن",
            "الأردن",
            "لبنان",
            "سوريا",
            "فلسطين",
            "ليبيا",
            "تونس",
            "الجزائر",
            "المغرب",
            "السودان",
            "الصومال",
        )

    /** True when [text] contains at least one Arabic-script code point. */
    fun isArabicScript(text: String): Boolean {
        for (c in text) {
            val code = c.code
            if (code in 0x0600..0x06FF ||
                code in 0x0750..0x077F ||
                code in 0x08A0..0x08FF ||
                code in 0xFB50..0xFDFF ||
                code in 0xFE70..0xFEFF
            ) {
                return true
            }
        }
        return false
    }

    fun isArabCountry(countryCode: String?): Boolean =
        when (countryCode?.uppercase()) {
            "IQ", "SA", "YE", "OM", "AE", "QA", "BH", "KW", "JO", "LB", "SY",
            "PS", "EG", "LY", "TN", "DZ", "MA", "EH", "MR", "SD", "SO", "DJ", "KM",
            -> true
            else -> false
        }

    /**
     * Region tier for a note: 0 = Iraq, 1 = other Arab, 2 = everywhere else.
     * Uses (in order) region hashtags, the geohash `g` tag, then the content script.
     */
    fun tier(note: Note): Int {
        val event = note.event ?: return TIER_GLOBAL

        val tags = event.tags.hashtags()
        if (tags.isNotEmpty()) {
            for (raw in tags) {
                val tag = raw.lowercase()
                if (tag in IRAQ_TAGS) return TIER_HOME
            }
            for (raw in tags) {
                val tag = raw.lowercase()
                if (tag in ARAB_TAGS) return TIER_ARAB
            }
        }

        val geoHash = event.getGeoHash()
        if (geoHash != null) {
            val decoded = GeoHash.decode(geoHash)
            if (decoded != null) {
                val lat = decoded.centerLat
                val lon = decoded.centerLon
                if (IRAQ.contains(lat, lon)) return TIER_HOME
                if (ARAB_COUNTRIES.any { it.contains(lat, lon) }) return TIER_ARAB
            }
        }

        if (isArabicScript(event.content)) return TIER_ARAB

        return TIER_GLOBAL
    }
}

/**
 * Stable re-tier of an already-ordered list: Iraqi content first, then the wider
 * Arab world, then everything else. Within a tier the incoming order is preserved.
 *
 * The tier is snapshotted once per note (mirroring [sortedByDefaultFeedOrder]) so a
 * note whose event is swapped by a relay mid-sort cannot make the comparator
 * inconsistent, which would otherwise throw from TimSort.
 */
fun Iterable<Note>.astroRegionRanked(): List<Note> =
    map { it to AstroRegionRank.tier(it) }
        .sortedWith(compareBy { it.second })
        .map { it.first }
