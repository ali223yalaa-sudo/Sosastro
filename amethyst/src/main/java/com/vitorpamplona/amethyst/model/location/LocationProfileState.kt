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

import androidx.compose.runtime.Stable
import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.service.location.CachedReversedGeoLocations
import com.vitorpamplona.amethyst.service.location.LocationState
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Private location profile (economy Worker): country from edge IP, optional GPS geohash.
 * Powers Around Me via nearby author pubkeys — not Nostr geotags/hashtags.
 */
@Stable
class LocationProfileState(
    private val account: Account,
    private val locationFlow: () -> StateFlow<LocationState.LocationResult>,
    private val scope: CoroutineScope,
    private val client: LocationApiClient = LocationApiClient(),
) {
    private val _nearbyAuthors = MutableStateFlow<Set<String>>(emptySet())
    val nearbyAuthors: StateFlow<Set<String>> = _nearbyAuthors.asStateFlow()

    private val _country = MutableStateFlow<String?>(null)
    val country: StateFlow<String?> = _country.asStateFlow()

    private val _region = MutableStateFlow<String?>(null)
    val region: StateFlow<String?> = _region.asStateFlow()

    private val _geohash = MutableStateFlow<String?>(null)
    val geohash: StateFlow<String?> = _geohash.asStateFlow()

    private val _scopeLabel = MutableStateFlow("none")
    val scopeLabel: StateFlow<String> = _scopeLabel.asStateFlow()

    private var lastPushedGeohash: String? = null
    private var refreshJob: Job? = null

    init {
        scope.launch {
            // Prefer region when GPS known; server falls back to country automatically.
            while (isActive) {
                refreshNearby()
                delay(NEARBY_REFRESH_MS)
            }
        }
        scope.launch {
            locationFlow().collectLatest { result ->
                if (result is LocationState.LocationResult.Success) {
                    pushGpsIfChanged(result)
                }
            }
        }
    }

    /** Consent + country (IP/edge). Call after signup or when user enables location feeds. */
    suspend fun registerConsent(
        lat: Double? = null,
        lon: Double? = null,
        geohash: String? = null,
    ) {
        client
            .updateLocation(account, lat = lat, lon = lon, geohash = geohash)
            .onSuccess { status ->
                _country.value = status.country
                _region.value = status.region
                _geohash.value = status.geohash
                if (!geohash.isNullOrBlank()) lastPushedGeohash = geohash.lowercase()
                refreshNearby()
            }.onFailure { e ->
                Log.w("LocationProfile") { "registerConsent failed: ${e.message}" }
            }
    }

    suspend fun refreshNearby() {
        client
            .fetchNearbyAuthors(account, scope = "region")
            .onSuccess { result ->
                _nearbyAuthors.value = result.authors
                _country.value = result.country ?: _country.value
                _scopeLabel.value = result.scope
            }.onFailure { e ->
                Log.w("LocationProfile") { "nearby failed: ${e.message}" }
            }
    }

    private suspend fun pushGpsIfChanged(result: LocationState.LocationResult.Success) {
        val geohash = result.geoHash.toString().lowercase()
        if (geohash == lastPushedGeohash) return
        val regionLabel = CachedReversedGeoLocations.cached(geohash)
        client
            .updateLocation(
                account,
                lat = result.geoHash.centerLat,
                lon = result.geoHash.centerLon,
                geohash = geohash,
                region = regionLabel,
            ).onSuccess { status ->
                lastPushedGeohash = geohash
                _country.value = status.country
                _region.value = status.region ?: regionLabel
                _geohash.value = status.geohash ?: geohash
                refreshNearby()
            }.onFailure { e ->
                Log.w("LocationProfile") { "gps update failed: ${e.message}" }
            }
    }

    /** Push reverse-geocoded city name once geocoder resolves (Around Me label). */
    suspend fun pushRegionLabel(region: String) {
        val trimmed = region.trim()
        if (trimmed.isBlank() || trimmed == _region.value) return
        client
            .updateLocation(account, region = trimmed)
            .onSuccess { status ->
                _region.value = status.region ?: trimmed
                _country.value = status.country ?: _country.value
                _geohash.value = status.geohash ?: _geohash.value
            }.onFailure { e ->
                Log.w("LocationProfile") { "region label update failed: ${e.message}" }
            }
    }

    companion object {
        private const val NEARBY_REFRESH_MS = 5 * 60 * 1000L
    }
}
