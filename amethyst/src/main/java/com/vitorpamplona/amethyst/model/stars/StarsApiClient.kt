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
package com.vitorpamplona.amethyst.model.stars

import com.vitorpamplona.amethyst.model.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class StarPackage(
    val id: String,
    val stars: Int,
    val priceCents: Int,
    val currencyCode: String,
    val label: String,
)

data class GiftSku(
    val id: String,
    val starPrice: Int,
    val emoji: String,
    val labelAr: String,
    val labelEn: String,
)

data class StarsCatalog(
    val checkoutEnabled: Boolean,
    val packages: List<StarPackage>,
    val gifts: List<GiftSku>,
    val dailyStars: Int = 10,
)

data class StarsSnapshot(
    val stars: Int = 0,
    val inventory: Map<String, Int> = emptyMap(),
    val catalog: StarsCatalog? = null,
    val dailyClaimed: Boolean = false,
    val dailyStars: Int = 10,
    val dayUtc: String = "",
)

data class NoteGiftSummary(
    val total: Int,
    val bySku: Map<String, Int>,
)

/**
 * NIP-98 signed economy calls. Prices are never taken from the client body.
 */
class StarsApiClient(
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build(),
) {
    suspend fun fetchCatalog(): Result<StarsCatalog> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = StarsApiConfig.absoluteUrl("/v1/stars/catalog")
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .header("Accept", "application/json")
                        .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error("stars_catalog_${resp.code}: ${body.take(200)}")
                    parseCatalog(body)
                }
            }
        }

    suspend fun fetchWallet(account: Account): Result<StarsSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = StarsApiConfig.absoluteUrl("/v1/wallet")
                signedGet(account, url).use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error("stars_wallet_${resp.code}: ${body.take(200)}")
                    parseWallet(body)
                }
            }
        }

    suspend fun buyGift(
        account: Account,
        skuId: String,
    ): Result<StarsSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = StarsApiConfig.absoluteUrl("/v1/stars/buy")
                val payload =
                    JSONObject()
                        .put("skuId", skuId)
                        .put("idempotencyKey", UUID.randomUUID().toString())
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                signedPost(account, url, payload).use { resp ->
                    val body = resp.body.string()
                    if (resp.code == 402) error("insufficient")
                    if (!resp.isSuccessful) error("stars_buy_${resp.code}: ${body.take(200)}")
                    parseWallet(body)
                }
            }
        }

    suspend fun sendGift(
        account: Account,
        skuId: String,
        toPubkey: String,
        noteId: String?,
    ): Result<StarsSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = StarsApiConfig.absoluteUrl("/v1/stars/send")
                val payload =
                    JSONObject()
                        .put("skuId", skuId)
                        .put("toPubkey", toPubkey.lowercase())
                        .put("idempotencyKey", UUID.randomUUID().toString())
                        .apply {
                            if (!noteId.isNullOrBlank()) put("noteId", noteId.lowercase())
                        }.toString()
                        .toByteArray(Charsets.UTF_8)
                signedPost(account, url, payload).use { resp ->
                    val body = resp.body.string()
                    if (resp.code == 402) error("insufficient")
                    if (resp.code == 400 && body.contains("SelfGift")) error("self")
                    if (!resp.isSuccessful) error("stars_send_${resp.code}: ${body.take(200)}")
                    if (!noteId.isNullOrBlank()) noteGiftCache.remove(noteId.lowercase())
                    parseWallet(body)
                }
            }
        }

    suspend fun createStarTopup(
        account: Account,
        packageId: String,
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = StarsApiConfig.absoluteUrl("/v1/stars/topup/create")
                val payload =
                    JSONObject()
                        .put("packageId", packageId)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                signedPost(account, url, payload).use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error("stars_topup_${resp.code}: ${body.take(200)}")
                    JSONObject(body).optBoolean("ok")
                }
            }
        }

    suspend fun claimDaily(account: Account): Result<StarsSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = StarsApiConfig.absoluteUrl("/v1/stars/daily/claim")
                val payload = "{}".toByteArray(Charsets.UTF_8)
                signedPost(account, url, payload).use { resp ->
                    val body = resp.body.string()
                    if (resp.code == 409) error("already_claimed")
                    if (!resp.isSuccessful) error("stars_daily_${resp.code}: ${body.take(200)}")
                    val o = JSONObject(body)
                    StarsSnapshot(
                        stars = o.optInt("stars"),
                        dailyClaimed = true,
                        dailyStars = o.optJSONObject("reward")?.optInt("stars") ?: o.optInt("granted"),
                        dayUtc = o.optString("dayUtc"),
                    )
                }
            }
        }

    suspend fun fetchProfileGifts(pubkeyHex: String): Result<NoteGiftSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = pubkeyHex.lowercase()
                val cached = profileGiftCache[id]
                val now = System.currentTimeMillis()
                if (cached != null && now - cached.first < NOTE_GIFT_TTL_MS) {
                    return@runCatching cached.second
                }
                val url = StarsApiConfig.absoluteUrl("/v1/stars/gifts/profile/$id")
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .header("Accept", "application/json")
                        .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error("stars_profile_${resp.code}")
                    val o = JSONObject(body)
                    val by = o.optJSONObject("bySku")
                    val map = mutableMapOf<String, Int>()
                    if (by != null) {
                        val keys = by.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            map[k] = by.optInt(k)
                        }
                    }
                    val summary = NoteGiftSummary(total = o.optInt("total"), bySku = map)
                    profileGiftCache[id] = now to summary
                    summary
                }
            }
        }

    suspend fun fetchNoteGifts(noteId: String): Result<NoteGiftSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = noteId.lowercase()
                val cached = noteGiftCache[id]
                val now = System.currentTimeMillis()
                if (cached != null && now - cached.first < NOTE_GIFT_TTL_MS) {
                    return@runCatching cached.second
                }
                val url = StarsApiConfig.absoluteUrl("/v1/stars/gifts/note/$id")
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .header("Accept", "application/json")
                        .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error("stars_note_${resp.code}")
                    val o = JSONObject(body)
                    val by = o.optJSONObject("bySku")
                    val map = mutableMapOf<String, Int>()
                    if (by != null) {
                        val keys = by.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            map[k] = by.optInt(k)
                        }
                    }
                    val summary = NoteGiftSummary(total = o.optInt("total"), bySku = map)
                    noteGiftCache[id] = now to summary
                    summary
                }
            }
        }

    private fun parseCatalog(raw: String): StarsCatalog {
        val o = JSONObject(raw)
        val packages = mutableListOf<StarPackage>()
        val pArr = o.optJSONArray("packages")
        if (pArr != null) {
            for (i in 0 until pArr.length()) {
                val p = pArr.getJSONObject(i)
                packages.add(
                    StarPackage(
                        id = p.optString("id"),
                        stars = p.optInt("stars"),
                        priceCents = p.optInt("priceCents"),
                        currencyCode = p.optString("currencyCode", "USD"),
                        label = p.optString("label"),
                    ),
                )
            }
        }
        val gifts = mutableListOf<GiftSku>()
        val gArr = o.optJSONArray("gifts")
        if (gArr != null) {
            for (i in 0 until gArr.length()) {
                val g = gArr.getJSONObject(i)
                gifts.add(
                    GiftSku(
                        id = g.optString("id"),
                        starPrice = g.optInt("starPrice"),
                        emoji = g.optString("emoji"),
                        labelAr = g.optString("labelAr"),
                        labelEn = g.optString("labelEn"),
                    ),
                )
            }
        }
        return StarsCatalog(
            checkoutEnabled = o.optBoolean("checkoutEnabled"),
            packages = packages,
            gifts = gifts,
            dailyStars = o.optJSONObject("dailyGift")?.optInt("stars") ?: 10,
        )
    }

    private fun parseWallet(raw: String): StarsSnapshot {
        val o = JSONObject(raw)
        val inv = mutableMapOf<String, Int>()
        val arr = o.optJSONArray("inventory")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                inv[it.optString("skuId")] = it.optInt("qty")
            }
        }
        val daily = o.optJSONObject("starDaily")
        return StarsSnapshot(
            stars = o.optInt("stars"),
            inventory = inv,
            dailyClaimed = daily?.optBoolean("claimed") ?: false,
            dailyStars = daily?.optInt("stars") ?: 10,
            dayUtc = daily?.optString("dayUtc").orEmpty(),
        )
    }

    private suspend fun signedGet(
        account: Account,
        url: String,
    ) = run {
        val auth = account.createHTTPAuthorization(url, "GET", null)
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .header("Authorization", auth.toAuthToken())
                .header("Accept", "application/json")
                .build()
        http.newCall(request).execute()
    }

    private suspend fun signedPost(
        account: Account,
        url: String,
        body: ByteArray,
    ) = run {
        val auth = account.createHTTPAuthorization(url, "POST", body)
        val request =
            Request
                .Builder()
                .url(url)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Authorization", auth.toAuthToken())
                .header("Accept", "application/json")
                .build()
        http.newCall(request).execute()
    }
}

private val noteGiftCache = ConcurrentHashMap<String, Pair<Long, NoteGiftSummary>>()
private val profileGiftCache = ConcurrentHashMap<String, Pair<Long, NoteGiftSummary>>()
private const val NOTE_GIFT_TTL_MS = 30_000L

internal object StarsApiConfig {
    const val BASE_HTTPS = "https://amethyst-ludo-economy.www-hmzhh123-com.workers.dev"

    fun absoluteUrl(pathAndQuery: String): String {
        val path = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
        return BASE_HTTPS.trimEnd('/') + path
    }
}
