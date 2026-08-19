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
package com.vitorpamplona.amethyst.model.location

import com.vitorpamplona.amethyst.model.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeoConsentStatus(
    val consent: Boolean,
    val country: String?,
    val consentAt: Long?,
)

data class LocationProfileStatus(
    val consent: Boolean,
    val country: String?,
    val region: String?,
    val geohash: String?,
    val consentAt: Long?,
    val updatedAt: Long?,
)

data class NearbyAuthorsResult(
    val country: String?,
    val scope: String,
    val authors: Set<String>,
)

/**
 * NIP-98 signed calls for private location / geo consent on the economy Worker.
 * Shared worker host; not game-related.
 */
class LocationApiClient(
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build(),
) {
    /**
     * Registers explicit regional-ads consent. Country is derived server-side
     * (Cloudflare edge); the client never sends an IP.
     */
    suspend fun registerGeoConsent(account: Account): Result<GeoConsentStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = LocationApiConfig.absoluteUrl(LocationApiConfig.GEO_CONSENT_PATH)
                val payload = """{"consent":true}""".toByteArray(Charsets.UTF_8)
                signedPost(account, url, payload).use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error("geo_consent_http_${resp.code}: ${body.take(240)}")
                    parseGeoConsent(body)
                }
            }
        }

    /**
     * Registers location consent. Country comes from Cloudflare edge;
     * optional [lat]/[lon]/[geohash] refine region privately (never published to Nostr).
     */
    suspend fun updateLocation(
        account: Account,
        lat: Double? = null,
        lon: Double? = null,
        geohash: String? = null,
        region: String? = null,
    ): Result<LocationProfileStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = LocationApiConfig.absoluteUrl(LocationApiConfig.LOCATION_UPDATE_PATH)
                val payload =
                    JSONObject()
                        .put("consent", true)
                        .apply {
                            if (lat != null) put("lat", lat)
                            if (lon != null) put("lon", lon)
                            if (!geohash.isNullOrBlank()) put("geohash", geohash)
                            if (!region.isNullOrBlank()) put("region", region)
                        }.toString()
                        .toByteArray(Charsets.UTF_8)
                signedPost(account, url, payload).use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error("location_update_http_${resp.code}: ${body.take(240)}")
                    parseLocationProfile(body)
                }
            }
        }

    /** Authors in the same country (or region when GPS geohash is known). */
    suspend fun fetchNearbyAuthors(
        account: Account,
        scope: String = "region",
    ): Result<NearbyAuthorsResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = "${LocationApiConfig.LOCATION_NEARBY_PATH}?scope=$scope"
                val url = LocationApiConfig.absoluteUrl(path)
                signedGet(account, url).use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error("location_nearby_http_${resp.code}: ${body.take(240)}")
                    parseNearbyAuthors(body)
                }
            }
        }

    private fun parseGeoConsent(raw: String): GeoConsentStatus {
        val o = JSONObject(raw)
        return GeoConsentStatus(
            consent = o.optBoolean("consent"),
            country = o.optString("country").takeIf { it.isNotBlank() && it != "null" },
            consentAt = if (o.isNull("consentAt")) null else o.optLong("consentAt"),
        )
    }

    private fun parseLocationProfile(raw: String): LocationProfileStatus {
        val o = JSONObject(raw)
        return LocationProfileStatus(
            consent = o.optBoolean("consent"),
            country = o.optString("country").takeIf { it.isNotBlank() && it != "null" },
            region = o.optString("region").takeIf { it.isNotBlank() && it != "null" },
            geohash = o.optString("geohash").takeIf { it.isNotBlank() && it != "null" },
            consentAt = if (o.isNull("consentAt")) null else o.optLong("consentAt"),
            updatedAt = if (o.isNull("updatedAt")) null else o.optLong("updatedAt"),
        )
    }

    private fun parseNearbyAuthors(raw: String): NearbyAuthorsResult {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("authors")
        val authors = linkedSetOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val hex = arr.optString(i)
                if (hex.isNotBlank()) authors.add(hex.lowercase())
            }
        }
        return NearbyAuthorsResult(
            country = o.optString("country").takeIf { it.isNotBlank() && it != "null" },
            scope = o.optString("scope", "none"),
            authors = authors,
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

private object LocationApiConfig {
    const val BASE_HTTPS = "https://amethyst-ludo-economy.www-hmzhh123-com.workers.dev"
    const val GEO_CONSENT_PATH = "/v1/geo/consent"
    const val LOCATION_UPDATE_PATH = "/v1/location/update"
    const val LOCATION_NEARBY_PATH = "/v1/location/nearby"

    fun absoluteUrl(pathAndQuery: String): String {
        val path = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
        return BASE_HTTPS.trimEnd('/') + path
    }
}
