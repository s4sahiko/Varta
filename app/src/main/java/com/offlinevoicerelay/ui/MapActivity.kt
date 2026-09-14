package com.offlinevoicerelay.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.offlinevoicerelay.R
import com.offlinevoicerelay.databinding.ActivityMapBinding
import com.offlinevoicerelay.vad.VadForegroundService
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ultra-Clear Google Maps HD Streets tile provider:
 * Uses high-contrast building footprints, road vectors, and landmark typography.
 */
class GoogleRoadsHDTileSource : OnlineTileSourceBase(
    "Google Streets HD (Ultra-Clear)",
    0, 22, 256, ".png",
    arrayOf(
        "https://mt0.google.com",
        "https://mt1.google.com",
        "https://mt2.google.com",
        "https://mt3.google.com"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl/vt/lyrs=m&hl=en&gl=in&x=$x&y=$y&z=$zoom"
    }
}

/**
 * Ultra-Clear Google Hybrid Satellite: Real aerial imagery with sharp street & building labels.
 */
class GoogleHybridHDTileSource : OnlineTileSourceBase(
    "Google Satellite HD (Aerial Details)",
    0, 21, 256, ".jpg",
    arrayOf(
        "https://mt0.google.com",
        "https://mt1.google.com",
        "https://mt2.google.com",
        "https://mt3.google.com"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl/vt/lyrs=y&hl=en&gl=in&x=$x&y=$y&z=$zoom"
    }
}

/**
 * Esri World Imagery (High-Resolution Sub-Meter Aerial Satellite)
 */
class EsriSatelliteTileSource : OnlineTileSourceBase(
    "Esri Sub-Meter Satellite",
    0, 19, 256, ".jpg",
    arrayOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$y/$x"
    }
}

class MapActivity : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityMapBinding
    private var locationManager: LocationManager? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var compassOverlay: CompassOverlay? = null
    private var rotationGestureOverlay: RotationGestureOverlay? = null
    private var currentLocation: Location? = null
    private var hasCenteredInitialLocation = false
    private var isAutoRotateEnabled = false

    // High-Definition Tile Sources for Maximum Clarity
    private val tileSources = listOf(
        GoogleRoadsHDTileSource(),
        GoogleHybridHDTileSource(),
        EsriSatelliteTileSource(),
        XYTileSource(
            "OpenStreetMap HD",
            0, 19, 256, ".png",
            arrayOf(
                "https://tile.openstreetmap.de/",
                "https://a.tile.openstreetmap.org/",
                "https://b.tile.openstreetmap.org/"
            )
        )
    )
    private var currentTileSourceIndex = 0

    // Routing and Waypoints
    private var currentTravelMode = "driving" // "driving" or "walking"
    private var isFollowingRoute = false
    private var currentRouteResult: LandRouteResult? = null
    private var startPlaceName: String = ""
    private var destPlaceName: String = ""
    private var startGeoPoint: GeoPoint? = null
    private var destGeoPoint: GeoPoint? = null

    private val routeMarkers = mutableListOf<Marker>()
    private var routePolyline: Polyline? = null

    // Mesh Service Connection
    private var service: VadForegroundService? = null
    private var bound = false
    private val peerMarkers = mutableListOf<Marker>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VadForegroundService.LocalBinder).getService()
            bound = true
            observeServicePeers()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            setupLocationUpdates()
        } else {
            binding.gpsStatusText.text = "GPS: Permission Denied"
            Toast.makeText(this, "Location permission required for real GPS tracking", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Optimize OSMDroid for ultra-fast, multi-threaded tile loading & deep local caching
        Configuration.getInstance().apply {
            load(this@MapActivity, PreferenceManager.getDefaultSharedPreferences(this@MapActivity))
            userAgentValue = "Mozilla/5.0 (Linux; Android 14; VartaTacticalMesh/1.0)"
            tileDownloadThreads = 16 // 16 parallel download threads for instantaneous rendering
            tileDownloadMaxQueueSize = 150
            cacheMapTileCount = 250.toShort() // High in-memory RAM cache
            tileFileSystemCacheMaxBytes = 800L * 1024 * 1024 // 800MB disk cache
            tileFileSystemCacheTrimBytes = 650L * 1024 * 1024
        }

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMapView()
        setupListeners()
        checkAndRequestLocationPermission()
        bindMeshService()
    }

    private fun setupMapView() {
        binding.mapView.apply {
            setTileSource(tileSources[0]) // Default: Ultra-Clear Google Streets HD
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            maxZoomLevel = 22.0 // Deep micro-zoom
            minZoomLevel = 3.0
            controller.setZoom(19.0) // Deep razor-sharp street & building view
        }

        // Setup Real Device Location Overlay
        val provider = GpsMyLocationProvider(this)
        myLocationOverlay = MyLocationNewOverlay(provider, binding.mapView).apply {
            enableMyLocation()
            enableFollowLocation()
            setDrawAccuracyEnabled(true)
            runOnFirstFix {
                runOnUiThread {
                    val myLoc = myLocation
                    if (myLoc != null && !hasCenteredInitialLocation) {
                        hasCenteredInitialLocation = true
                        binding.mapView.controller.animateTo(myLoc)
                        binding.mapView.controller.setZoom(19.0)
                    }
                }
            }
        }
        binding.mapView.overlays.add(myLocationOverlay)

        // Setup Compass Overlay
        compassOverlay = CompassOverlay(this, InternalCompassOrientationProvider(this), binding.mapView).apply {
            enableCompass()
        }
        binding.mapView.overlays.add(compassOverlay)

        // Setup 360-degree Two-Finger Multi-Touch Manual Rotation Gesture
        rotationGestureOverlay = RotationGestureOverlay(binding.mapView).apply {
            isEnabled = true
        }
        binding.mapView.overlays.add(rotationGestureOverlay)
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.toggleSearchPanelButton.setColorFilter(Color.parseColor("#FFFFFF"))
        binding.toggleSearchPanelButton.setOnClickListener {
            val willBeVisible = binding.routePlannerCard.visibility != View.VISIBLE
            binding.routePlannerCard.visibility = if (willBeVisible) View.VISIBLE else View.GONE
            binding.toggleSearchPanelButton.setColorFilter(
                if (willBeVisible) Color.parseColor("#10B981") else Color.parseColor("#FFFFFF")
            )
        }

        binding.useGpsOriginButton.setOnClickListener {
            binding.startPlaceInput.setText("My Location")
            startGeoPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) } ?: myLocationOverlay?.myLocation
            Toast.makeText(this, "Start set to My Location", Toast.LENGTH_SHORT).show()
        }

        binding.clearDestButton.setOnClickListener {
            binding.destPlaceInput.text?.clear()
        }

        binding.swapPlacesButton.setOnClickListener {
            val startText = binding.startPlaceInput.text.toString()
            val destText = binding.destPlaceInput.text.toString()
            binding.startPlaceInput.setText(destText)
            binding.destPlaceInput.setText(startText)

            val tempGeo = startGeoPoint
            startGeoPoint = destGeoPoint
            destGeoPoint = tempGeo

            if (startGeoPoint != null && destGeoPoint != null) {
                executeLandRouteCalculation(startGeoPoint!!, destGeoPoint!!)
            }
        }

        binding.modeDriveButton.setOnClickListener {
            setTravelMode("driving")
        }

        binding.modeWalkButton.setOnClickListener {
            setTravelMode("walking")
        }

        binding.plotLandRouteButton.setOnClickListener {
            hideKeyboard()
            plotRouteFromInputs()
        }

        binding.destPlaceInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                plotRouteFromInputs()
                true
            } else false
        }

        // Compass FAB: Re-aligns map to North (0°)
        binding.compassFab.setOnClickListener {
            binding.mapView.mapOrientation = 0f
            binding.compassFab.rotation = 0f
            isAutoRotateEnabled = false
            binding.autoRotateFab.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
            Toast.makeText(this, "Map Aligned to North (0°)", Toast.LENGTH_SHORT).show()
        }

        // Auto-Rotate FAB: Toggles heading alignment mode
        binding.autoRotateFab.setOnClickListener {
            isAutoRotateEnabled = !isAutoRotateEnabled
            if (isAutoRotateEnabled) {
                binding.autoRotateFab.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                myLocationOverlay?.enableFollowLocation()
                currentLocation?.let { loc ->
                    val bearing = if (loc.hasBearing()) loc.bearing else (compassOverlay?.orientation ?: 0f)
                    if (bearing != 0f) {
                        binding.mapView.mapOrientation = -bearing
                        binding.compassFab.rotation = -bearing
                    }
                }
                Toast.makeText(this, "Auto-Rotate & Center Active", Toast.LENGTH_SHORT).show()
            } else {
                binding.autoRotateFab.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
                Toast.makeText(this, "Manual 360° Free Rotation Active", Toast.LENGTH_SHORT).show()
            }
        }

        binding.myLocationFab.setOnClickListener {
            centerOnMyLocation()
        }

        binding.zoomInFab.setOnClickListener {
            binding.mapView.controller.zoomIn()
        }

        binding.zoomOutFab.setOnClickListener {
            binding.mapView.controller.zoomOut()
        }

        binding.clearRouteFab.setOnClickListener {
            clearRoute()
        }

        binding.followRouteButton.setOnClickListener {
            if (currentRouteResult != null && destGeoPoint != null) {
                isFollowingRoute = !isFollowingRoute
                if (isFollowingRoute) {
                    binding.followRouteButton.text = "Stop Navigation"
                    binding.followRouteButton.setBackgroundColor(Color.parseColor("#EF4444"))
                    isAutoRotateEnabled = true
                    binding.autoRotateFab.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                    centerOnMyLocation()
                    Toast.makeText(this, "Land Navigation & Auto-Rotate Active", Toast.LENGTH_SHORT).show()
                } else {
                    binding.followRouteButton.text = "Start Following Route"
                    binding.followRouteButton.setBackgroundColor(Color.parseColor("#10B981"))
                }
            } else {
                Toast.makeText(this, "Please plot a route first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.layerToggleButton.setOnClickListener {
            currentTileSourceIndex = (currentTileSourceIndex + 1) % tileSources.size
            val newSource = tileSources[currentTileSourceIndex]
            binding.mapView.setTileSource(newSource)
            Toast.makeText(this, "Layer: ${newSource.name()}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTravelMode(mode: String) {
        currentTravelMode = mode
        if (mode == "driving") {
            binding.modeDriveButton.setBackgroundResource(R.drawable.bg_pill_active)
            binding.modeDriveButton.setTextColor(Color.parseColor("#FFFFFF"))
            binding.modeWalkButton.setBackgroundResource(0)
            binding.modeWalkButton.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            binding.modeWalkButton.setBackgroundResource(R.drawable.bg_pill_active)
            binding.modeWalkButton.setTextColor(Color.parseColor("#FFFFFF"))
            binding.modeDriveButton.setBackgroundResource(0)
            binding.modeDriveButton.setTextColor(Color.parseColor("#94A3B8"))
        }

        if (startGeoPoint != null && destGeoPoint != null) {
            executeLandRouteCalculation(startGeoPoint!!, destGeoPoint!!)
        }
    }

    private fun plotRouteFromInputs() {
        val startQuery = binding.startPlaceInput.text.toString().trim()
        val destQuery = binding.destPlaceInput.text.toString().trim()

        if (destQuery.isEmpty()) {
            Toast.makeText(this, "Please enter a destination place name", Toast.LENGTH_SHORT).show()
            binding.destPlaceInput.requestFocus()
            return
        }

        val effectiveStartQuery = if (startQuery.isEmpty()) "My Location" else startQuery

        binding.routeProgressBar.visibility = View.VISIBLE
        binding.plotLandRouteButton.isEnabled = false

        lifecycleScope.launch {
            try {
                // 1. Geocode Start Place
                val startResult = LandRouteManager.geocodePlace(
                    effectiveStartQuery,
                    this@MapActivity,
                    currentLocation
                )

                if (startResult == null) {
                    Toast.makeText(
                        this@MapActivity,
                        "Could not locate start place: \"$effectiveStartQuery\". Check spelling or internet.",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.routeProgressBar.visibility = View.GONE
                    binding.plotLandRouteButton.isEnabled = true
                    return@launch
                }

                // 2. Geocode Destination Place
                val destResult = LandRouteManager.geocodePlace(
                    destQuery,
                    this@MapActivity,
                    currentLocation
                )

                if (destResult == null) {
                    Toast.makeText(
                        this@MapActivity,
                        "Could not locate destination: \"$destQuery\". Check spelling or internet.",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.routeProgressBar.visibility = View.GONE
                    binding.plotLandRouteButton.isEnabled = true
                    return@launch
                }

                startPlaceName = startResult.displayName
                destPlaceName = destResult.displayName
                startGeoPoint = startResult.point
                destGeoPoint = destResult.point

                executeLandRouteCalculation(startResult.point, destResult.point)

            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, "Error searching places: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.routeProgressBar.visibility = View.GONE
                binding.plotLandRouteButton.isEnabled = true
            }
        }
    }

    private fun executeLandRouteCalculation(startPoint: GeoPoint, destPoint: GeoPoint) {
        binding.routeProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val routeResult = LandRouteManager.calculateLandRoute(
                    startPoint,
                    destPoint,
                    currentTravelMode
                )
                currentRouteResult = routeResult
                renderLandRouteOnMap(startPoint, destPoint, routeResult)
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, "Route calculation failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.routeProgressBar.visibility = View.GONE
            }
        }
    }

    private fun renderLandRouteOnMap(
        startPoint: GeoPoint,
        destPoint: GeoPoint,
        routeResult: LandRouteResult
    ) {
        // Clear previous route overlays
        for (m in routeMarkers) {
            binding.mapView.overlays.remove(m)
        }
        routeMarkers.clear()

        routePolyline?.let {
            binding.mapView.overlays.remove(it)
            routePolyline = null
        }

        // Add Start Marker (Point A)
        val startMarker = Marker(binding.mapView).apply {
            position = startPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Start (Point A)"
            snippet = startPlaceName.ifEmpty { "Origin Location" }
            showInfoWindow()
        }
        routeMarkers.add(startMarker)
        binding.mapView.overlays.add(startMarker)

        // Add Destination Marker (Point B)
        val destMarker = Marker(binding.mapView).apply {
            position = destPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Destination (Point B)"
            snippet = destPlaceName.ifEmpty { "Target Location" }
        }
        routeMarkers.add(destMarker)
        binding.mapView.overlays.add(destMarker)

        // Draw Land Road Polyline
        val polyline = Polyline().apply {
            setPoints(routeResult.points)
            outlinePaint.color = Color.parseColor("#10B981")
            outlinePaint.strokeWidth = 14f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
        routePolyline = polyline
        binding.mapView.overlays.add(polyline)

        // Zoom and Frame the Route Bounds on Screen
        val allPoints = routeResult.points
        if (allPoints.isNotEmpty()) {
            val boundingBox = BoundingBox.fromGeoPoints(allPoints)
            binding.mapView.zoomToBoundingBox(boundingBox.increaseByScale(1.35f), true, 80)
        }

        binding.mapView.invalidate()

        // Update Bottom Tactical HUD Card
        binding.routeHeaderLayout.visibility = View.VISIBLE
        binding.routeActionButtons.visibility = View.VISIBLE
        binding.clearRouteFab.visibility = View.VISIBLE

        val distMeters = routeResult.totalDistanceMeters
        val distText = if (distMeters >= 1000) {
            String.format(Locale.US, "%.2f km", distMeters / 1000.0)
        } else {
            String.format(Locale.US, "%d m", distMeters.roundToInt())
        }

        val durationSec = routeResult.durationSeconds
        val totalMins = (durationSec / 60.0).roundToInt()
        val durationText = if (totalMins >= 60) {
            "${totalMins / 60}h ${totalMins % 60}m"
        } else {
            "$totalMins mins"
        }

        binding.metric1Label.text = "ROAD DISTANCE"
        binding.metric1Value.text = distText

        binding.metric2Label.text = "LAND PATH"
        binding.metric2Value.text = if (routeResult.isRoadNetwork) "Via ${routeResult.summary}" else "Direct Land Vector"

        binding.metric3Label.text = if (currentTravelMode == "driving") "DRIVE ETA" else "WALK ETA"
        binding.metric3Value.text = durationText

        binding.routeTitleText.text = if (routeResult.isRoadNetwork) "LAND ROAD ROUTE FOUND" else "OFFLINE LAND VECTOR"
        binding.routeInstructionText.text = "${routeResult.points.size} land waypoints plotted"

        Toast.makeText(this, "Plotted land route: $distText ($durationText)", Toast.LENGTH_SHORT).show()
    }

    private fun clearRoute() {
        startGeoPoint = null
        destGeoPoint = null
        currentRouteResult = null
        isFollowingRoute = false

        binding.followRouteButton.text = "Start Following Route"
        binding.followRouteButton.setBackgroundColor(Color.parseColor("#10B981"))
        binding.routeHeaderLayout.visibility = View.GONE
        binding.routeActionButtons.visibility = View.GONE
        binding.clearRouteFab.visibility = View.GONE

        for (m in routeMarkers) {
            binding.mapView.overlays.remove(m)
        }
        routeMarkers.clear()

        routePolyline?.let {
            binding.mapView.overlays.remove(it)
            routePolyline = null
        }

        binding.mapView.invalidate()

        binding.metric1Label.text = "COORDINATES"
        binding.metric2Label.text = "HEADING / SPEED"
        binding.metric3Label.text = "ACCURACY"
        updateGpsMetrics(currentLocation)

        Toast.makeText(this, "Route cleared", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let {
            imm?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun getCardinalDirection(bearing: Float): String {
        return when {
            bearing >= 337.5 || bearing < 22.5 -> "N"
            bearing >= 22.5 && bearing < 67.5 -> "NE"
            bearing >= 67.5 && bearing < 112.5 -> "E"
            bearing >= 112.5 && bearing < 157.5 -> "SE"
            bearing >= 157.5 && bearing < 202.5 -> "S"
            bearing >= 202.5 && bearing < 247.5 -> "SW"
            bearing >= 247.5 && bearing < 292.5 -> "W"
            else -> "NW"
        }
    }

    private fun checkAndRequestLocationPermission() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineLocationGranted || coarseLocationGranted) {
            setupLocationUpdates()
        } else {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun setupLocationUpdates() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1.0f,
                this
            )
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                1.0f,
                this
            )

            val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLocation = lastGps ?: lastNet
            if (bestLocation != null) {
                onLocationChanged(bestLocation)
                if (!hasCenteredInitialLocation) {
                    hasCenteredInitialLocation = true
                    binding.mapView.controller.setCenter(GeoPoint(bestLocation.latitude, bestLocation.longitude))
                    binding.mapView.controller.setZoom(19.0)
                }
            }
        } catch (e: SecurityException) {
            binding.gpsStatusText.text = "GPS: Access Denied"
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        binding.gpsStatusText.text = String.format(
            Locale.US,
            "GPS Fix: ±%.1fm • Alt: %.0fm",
            location.accuracy,
            location.altitude
        )

        if (!hasCenteredInitialLocation) {
            hasCenteredInitialLocation = true
            binding.mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            binding.mapView.controller.setZoom(19.0)
        }

        // Auto-Center & Auto-Rotate when navigating or auto-rotate mode is active
        if (isAutoRotateEnabled || isFollowingRoute) {
            myLocationOverlay?.enableFollowLocation()
            val userPoint = GeoPoint(location.latitude, location.longitude)
            binding.mapView.controller.animateTo(userPoint)

            val bearing = if (location.hasBearing() && location.bearing != 0f) {
                location.bearing
            } else {
                compassOverlay?.orientation ?: 0f
            }

            if (bearing != 0f) {
                binding.mapView.mapOrientation = -bearing
                binding.compassFab.rotation = -bearing
            }
        } else {
            binding.compassFab.rotation = binding.mapView.mapOrientation
        }

        if (currentRouteResult == null) {
            updateGpsMetrics(location)
        }

        if (isFollowingRoute && destGeoPoint != null) {
            val destination = destGeoPoint!!
            val results = FloatArray(3)
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                destination.latitude,
                destination.longitude,
                results
            )
            val distRemaining = results[0]
            val bearingToDest = (results[1] + 360) % 360

            val distText = if (distRemaining >= 1000) {
                String.format(Locale.US, "%.2f km", distRemaining / 1000f)
            } else {
                String.format(Locale.US, "%d m", distRemaining.roundToInt())
            }
            binding.metric1Value.text = distText
            binding.metric2Value.text = String.format(Locale.US, "%03d° %s", bearingToDest.roundToInt(), getCardinalDirection(bearingToDest))
            val etaMins = (distRemaining / (if (currentTravelMode == "walking") 1.4f else 11.0f) / 60f).roundToInt()
            binding.metric3Value.text = "$etaMins mins"
        }
    }

    private fun updateGpsMetrics(loc: Location?) {
        if (loc != null) {
            binding.metric1Value.text = String.format(Locale.US, "%.4f°, %.4f°", loc.latitude, loc.longitude)
            val speedKmh = loc.speed * 3.6f
            val bearing = if (loc.hasBearing()) loc.bearing else 0f
            val cardinal = getCardinalDirection(bearing)
            binding.metric2Value.text = String.format(Locale.US, "%03d° %s • %.1f km/h", bearing.roundToInt(), cardinal, speedKmh)
            binding.metric3Value.text = String.format(Locale.US, "±%.1f m", loc.accuracy)
        } else {
            binding.metric1Value.text = "Acquiring..."
            binding.metric2Value.text = "0° N • 0 km/h"
            binding.metric3Value.text = "±-- m"
        }
    }

    private fun centerOnMyLocation() {
        val loc = currentLocation
        if (loc != null) {
            val point = GeoPoint(loc.latitude, loc.longitude)
            binding.mapView.controller.animateTo(point)
            binding.mapView.controller.setZoom(19.0)
            Toast.makeText(this, "Centered on Real Location", Toast.LENGTH_SHORT).show()
        } else {
            val lastLoc = myLocationOverlay?.myLocation
            if (lastLoc != null) {
                binding.mapView.controller.animateTo(lastLoc)
                binding.mapView.controller.setZoom(19.0)
                Toast.makeText(this, "Centered on GPS Location", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Acquiring GPS location...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindMeshService() {
        val intent = Intent(this, VadForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServicePeers() {
        val s = service ?: return
        lifecycleScope.launch {
            s.status.collect {
                updatePeerMarkers()
            }
        }
    }

    private fun updatePeerMarkers() {
        val s = service ?: return
        val peers = s.status.value.connectedPeers

        for (m in peerMarkers) {
            binding.mapView.overlays.remove(m)
        }
        peerMarkers.clear()

        currentLocation?.let { userLoc ->
            peers.forEachIndexed { index, peer ->
                val angle = (index * 72.0) * Math.PI / 180.0
                val distanceOffset = 0.0008 + (index * 0.0004)
                val peerLat = userLoc.latitude + (distanceOffset * cos(angle))
                val peerLon = userLoc.longitude + (distanceOffset * sin(angle))

                val marker = Marker(binding.mapView).apply {
                    position = GeoPoint(peerLat.toDouble(), peerLon.toDouble())
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Mesh Peer: ${peer.displayName}"
                    snippet = "Node ID: ${peer.deviceId}\nTap to route to peer"
                    setOnMarkerClickListener { _, _ ->
                        Toast.makeText(this@MapActivity, "Selected Peer: ${peer.displayName}", Toast.LENGTH_SHORT).show()
                        destGeoPoint = position
                        binding.destPlaceInput.setText(peer.displayName)
                        val start = startGeoPoint ?: currentLocation?.let { GeoPoint(it.latitude, it.longitude) } ?: position
                        startGeoPoint = start
                        executeLandRouteCalculation(start, position)
                        showInfoWindow()
                        true
                    }
                }
                peerMarkers.add(marker)
                binding.mapView.overlays.add(marker)
            }
            binding.mapView.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        myLocationOverlay?.enableMyLocation()
        compassOverlay?.enableCompass()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        myLocationOverlay?.disableMyLocation()
        compassOverlay?.disableCompass()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager?.removeUpdates(this)
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
