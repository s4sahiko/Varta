package com.offlinevoicerelay.ui

import android.content.Context
import android.location.Geocoder
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class GeoPlaceResult(
    val displayName: String,
    val point: GeoPoint
)

data class LandRouteResult(
    val points: List<GeoPoint>,
    val totalDistanceMeters: Double,
    val durationSeconds: Double,
    val summary: String,
    val isRoadNetwork: Boolean
)

object LandRouteManager {

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; VartaTacticalMesh/1.0)"

    /**
     * Geocode place name to geographic coordinate:
     * 1. If "My Location", "Current Location", or empty -> Uses device's real live GPS location
     * 2. Native Android Geocoder for high accuracy real-world place search
     * 3. OpenStreetMap Nominatim Geocoding API for global places, landmarks, and addresses
     */
    suspend fun geocodePlace(
        query: String,
        context: Context,
        currentLocation: Location?
    ): GeoPlaceResult? = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            if (currentLocation != null) {
                return@withContext GeoPlaceResult(
                    displayName = "My Current Location",
                    point = GeoPoint(currentLocation.latitude, currentLocation.longitude)
                )
            }
            return@withContext null
        }

        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower == "my location" || lower == "current location" || lower == "me" || lower == "here" || lower == "gps") {
            if (currentLocation != null) {
                return@withContext GeoPlaceResult(
                    displayName = "My Current Location",
                    point = GeoPoint(currentLocation.latitude, currentLocation.longitude)
                )
            }
        }

        // Try Android Geocoder
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(trimmed, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val name = addr.getAddressLine(0) ?: addr.featureName ?: trimmed
                    return@withContext GeoPlaceResult(
                        displayName = name,
                        point = GeoPoint(addr.latitude, addr.longitude)
                    )
                }
            }
        } catch (e: Exception) {
            // Proceed to Nominatim
        }

        // Fallback: OpenStreetMap Nominatim Geocoder
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val urlString = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1&addressdetails=1"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.use { it.readText() }
                conn.disconnect()

                val jsonArray = JSONArray(response)
                if (jsonArray.length() > 0) {
                    val item = jsonArray.getJSONObject(0)
                    val lat = item.getDouble("lat")
                    val lon = item.getDouble("lon")
                    val displayName = item.optString("display_name", trimmed)
                    return@withContext GeoPlaceResult(
                        displayName = displayName.split(",").take(3).joinToString(",").trim(),
                        point = GeoPoint(lat, lon)
                    )
                }
            }
        } catch (e: Exception) {
            // Network fallback error
        }

        // Vicinity search fallback if current location is available
        if (currentLocation != null) {
            try {
                val vicinityQuery = URLEncoder.encode("$trimmed, Gujarat", "UTF-8")
                val urlString = "https://nominatim.openstreetmap.org/search?q=$vicinityQuery&format=json&limit=1"
                val url = URL(urlString)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.use { it.readText() }
                    conn.disconnect()
                    val jsonArray = JSONArray(response)
                    if (jsonArray.length() > 0) {
                        val item = jsonArray.getJSONObject(0)
                        return@withContext GeoPlaceResult(
                            displayName = item.optString("display_name", trimmed).split(",").take(3).joinToString(",").trim(),
                            point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        null
    }

    /**
     * Fetch actual land/road path between two geographic points using OSRM routing engine.
     * Mode can be "driving" (highways & roads) or "walking" (pedestrian paths).
     */
    suspend fun calculateLandRoute(
        start: GeoPoint,
        destination: GeoPoint,
        mode: String = "driving"
    ): LandRouteResult = withContext(Dispatchers.IO) {
        val routingProfile = if (mode == "walking") "walking" else "driving"
        val urlString = "https://router.project-osrm.org/route/v1/$routingProfile/" +
                "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&steps=true"

        try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.use { it.readText() }
                conn.disconnect()

                val json = JSONObject(response)
                val code = json.optString("code", "")
                if (code == "Ok") {
                    val routes = json.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val route = routes.getJSONObject(0)
                        val totalDistance = route.optDouble("distance", 0.0)
                        val totalDuration = route.optDouble("duration", 0.0)
                        val geometry = route.getJSONObject("geometry")
                        val coordinates = geometry.getJSONArray("coordinates")

                        val pointList = mutableListOf<GeoPoint>()
                        for (i in 0 until coordinates.length()) {
                            val coord = coordinates.getJSONArray(i)
                            val lon = coord.getDouble(0)
                            val lat = coord.getDouble(1)
                            pointList.add(GeoPoint(lat, lon))
                        }

                        val summary = if (route.has("legs")) {
                            val legs = route.getJSONArray("legs")
                            if (legs.length() > 0) {
                                legs.getJSONObject(0).optString("summary", "Land Road Route")
                            } else "Land Road Route"
                        } else "Land Road Route"

                        return@withContext LandRouteResult(
                            points = pointList,
                            totalDistanceMeters = totalDistance,
                            durationSeconds = totalDuration,
                            summary = summary,
                            isRoadNetwork = true
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Network failure / timeout
        }

        // Offline / Direct Land Vector Fallback
        val distResults = FloatArray(1)
        Location.distanceBetween(
            start.latitude, start.longitude,
            destination.latitude, destination.longitude,
            distResults
        )
        val directDistance = distResults[0].toDouble()
        val estimatedDuration = if (mode == "walking") directDistance / 1.4 else directDistance / 11.0

        LandRouteResult(
            points = listOf(start, destination),
            totalDistanceMeters = directDistance,
            durationSeconds = estimatedDuration,
            summary = "Direct Land Vector (Offline)",
            isRoadNetwork = false
        )
    }
}
