package com.khpalgps.splsrec.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import com.khpalgps.splsrec.R
import com.khpalgps.splsrec.databinding.ActivityMapBinding
import com.khpalgps.splsrec.spls.SplsGeo
import com.khpalgps.splsrec.spls.SplsLocation
import com.khpalgps.splsrec.spls.SplsSession
import com.khpalgps.splsrec.spls.SplsStore
import com.khpalgps.splsrec.utils.PrefKeys
import com.khpalgps.splsrec.utils.PrefManager
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.SupportMapFragment
import org.maplibre.android.maps.Style
import java.util.Locale

/**
 * The only screen: a key-free map with the SPLS markers (brain icon and serial number) and radius circles.
 * The SPLS button opens the recording pop-up; while an area is being picked it confirms the picked area.
 */
class MapActivity : AppCompatActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    private val splsController = SplsController(this)

    private lateinit var map: MapLibreMap
    private var styleReady = false
    private lateinit var locationClient: FusedLocationProviderClient
    private var tracking = false
    private var lastRealFix: Location? = null

    private val polygons = mutableListOf<Polygon>()
    private val markers = mutableListOf<Marker>()
    private var tempPolygon: Polygon? = null
    private var tempMarker: Marker? = null
    private var drawSignature = ""

    private var mapType = PrefManager.mapType
    private val satMaxZoom = MapStyles.DEFAULT_SAT_MAX_ZOOM

    // "Select another location": the user taps an area on the map and then taps the SPLS button
    private var pick = false
    private var picked = false
    private var pickLat = 0.0
    private var pickLon = 0.0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onLocationAllowed() }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastRealFix = loc
            // a circle that contains you is hidden, so redraw when your position changes
            drawSplsCircles(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.overlay) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        MapLibre.getInstance(this)
        val fragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction().replace(R.id.map, fragment).commit()
        fragment.getMapAsync(this)

        binding.zoomIn.setOnClickListener { if (::map.isInitialized) map.animateCamera(CameraUpdateFactory.zoomIn()) }
        binding.zoomOut.setOnClickListener { if (::map.isInitialized) map.animateCamera(CameraUpdateFactory.zoomOut()) }
        binding.getlocation.setOnClickListener { goToMyLocation() }
        binding.mapType.setOnClickListener { nextMapType() }
        binding.splsButton.setOnClickListener { splsButtonClicked() }
        binding.splsButton.setOnLongClickListener {
            if (pick) {
                exitPick()
                note("Selecting an SPLS area was cancelled.")
            }
            true
        }
        binding.searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(v.text.toString())
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }

        askLocation()
    }

    // ------------------------------------------------------------ location and map

    private fun hasLocation() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askLocation() {
        if (hasLocation()) onLocationAllowed()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @SuppressLint("MissingPermission")
    private fun onLocationAllowed() {
        if (!hasLocation()) return
        if (!tracking) {
            tracking = true
            locationClient = LocationServices.getFusedLocationProviderClient(this)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
        if (styleReady) map.getStyle { activateLocationDot(it, false) }
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        map = mapLibreMap
        with(map) {
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.isCompassEnabled = true
            uiSettings.setCompassMargins(0, dp(150), dp(16), 0)
            uiSettings.isLogoEnabled = true
            uiSettings.setLogoGravity(Gravity.BOTTOM or Gravity.START)
            uiSettings.setLogoMargins(dp(16), 0, 0, dp(48))
            uiSettings.isAttributionEnabled = true
            uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.END)
            uiSettings.setAttributionMargins(0, 0, dp(16), dp(48))
            addOnMapClickListener(this@MapActivity)
        }
        val fix = SplsFlow.currentFix(this)
        if (fix != null) {
            lastRealFix = fix
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 15.0))
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 10.0), 1.8))
        }
        applyStyle(true)
    }

    private fun applyStyle(firstTime: Boolean) {
        map.setStyle(Style.Builder().fromJson(MapStyles.styleJson(mapType, satMaxZoom))) { style ->
            styleReady = true
            activateLocationDot(style, firstTime)
            // a new style wipes the markers, so draw them again
            drawSplsCircles(true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun activateLocationDot(style: Style, @Suppress("UNUSED_PARAMETER") firstTime: Boolean) {
        if (!hasLocation()) return
        val component = map.locationComponent
        component.activateLocationComponent(LocationComponentActivationOptions.builder(this, style).useDefaultLocationEngine(true).build())
        component.isLocationComponentEnabled = true
        component.cameraMode = CameraMode.NONE
        component.renderMode = RenderMode.COMPASS
    }

    private fun nextMapType() {
        if (!::map.isInitialized) return
        val order = listOf(1, 2, 4, 3)
        mapType = order[(order.indexOf(mapType) + 1) % order.size]
        PrefManager.mapType = mapType
        val name = when (mapType) { 2 -> "Satellite"; 3 -> "Terrain"; 4 -> "Hybrid"; else -> "Street map" }
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        applyStyle(false)
    }

    private fun goToMyLocation() {
        if (!::map.isInitialized) return
        if (!hasLocation()) {
            askLocation()
            return
        }
        val fix = lastRealFix ?: SplsFlow.currentFix(this)
        if (fix == null) {
            Toast.makeText(this, "No location yet. Turn on location and wait a moment.", Toast.LENGTH_LONG).show()
            return
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 16.0))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun note(text: String) {
        Snackbar.make(binding.root, text, 5000).show()
    }

    // ------------------------------------------------------------ SPLS circles, markers and serial numbers

    private fun marker(serial: Int) = IconFactory.getInstance(this).fromBitmap(SplsMarker.bitmap(this, serial))

    /** Draws every SPLS circle with its marker; a circle that contains your real position is hidden. */
    private fun drawSplsCircles(force: Boolean) {
        if (!::map.isInitialized || !styleReady) return
        val here = lastRealFix
        val locations = SplsStore.locations(this)

        val sig = StringBuilder()
        val visible = ArrayList<Pair<Int, SplsLocation>>()
        locations.forEachIndexed { index, l ->
            if (!l.isCircle) return@forEachIndexed
            val inside = here != null && SplsGeo.distanceM(here.latitude, here.longitude, l.lat, l.lon) <= l.radius
            sig.append(l.id).append(index).append(l.lat).append(l.lon).append(l.radius).append(inside).append(';')
            if (!inside) visible.add(Pair(index + 1, l))
        }
        if (!force && sig.toString() == drawSignature) return
        drawSignature = sig.toString()

        polygons.forEach { map.removePolygon(it) }
        polygons.clear()
        markers.forEach { it.remove() }
        markers.clear()
        for ((serial, l) in visible) {
            val points = SplsGeo.circle(l.lat, l.lon, l.radius).map { LatLng(it.first, it.second) }
            polygons.add(map.addPolygon(PolygonOptions().addAll(points).fillColor(0x336750A4).strokeColor(0xFF6750A4.toInt())))
            markers.add(map.addMarker(MarkerOptions().position(LatLng(l.lat, l.lon)).icon(marker(serial))))
        }
    }

    private fun showPreview(lat: Double, lon: Double, radiusM: Double, serial: Int) {
        if (!::map.isInitialized) return
        tempPolygon?.let { map.removePolygon(it) }
        tempMarker?.remove()
        val points = SplsGeo.circle(lat, lon, radiusM).map { LatLng(it.first, it.second) }
        tempPolygon = map.addPolygon(PolygonOptions().addAll(points).fillColor(0x336750A4).strokeColor(0xFF6750A4.toInt()))
        tempMarker = map.addMarker(MarkerOptions().position(LatLng(lat, lon)).icon(marker(serial)))
    }

    private fun clearPreview() {
        tempPolygon?.let { map.removePolygon(it) }
        tempPolygon = null
        tempMarker?.remove()
        tempMarker = null
    }

    // ------------------------------------------------------------ picking another location

    private fun enterPick() {
        PrefManager.setRaw(PrefKeys.SPLS_PICK_PENDING, null)
        pick = true
        picked = false
        note("Search or tap the area on the map, then tap the SPLS button to add it. Long-press the SPLS button to cancel.")
    }

    private fun exitPick() {
        pick = false
        picked = false
        clearPreview()
        drawSplsCircles(true)
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (pick) {
            pickPoint(point.latitude, point.longitude)
        }
        return true
    }

    private fun pickPoint(lat: Double, lon: Double) {
        pickLat = lat
        pickLon = lon
        picked = true
        showPreview(lat, lon, SplsLocation.MIN_RADIUS, SplsStore.locations(this).size + 1)
    }

    private fun splsButtonClicked() {
        if (!pick) {
            SplsDialog.show(this, splsController, onPickArea = { enterPick() }, onChanged = { drawSplsCircles(true) })
            return
        }
        if (!picked) {
            note("Tap a place on the map first, or search for one. Then tap the SPLS button.")
            return
        }
        val lat = pickLat
        val lon = pickLon
        SplsPrompts.askName(this, "Name this SPLS location", "", SplsStore.locations(this).map { it.name }, { name ->
            val serial = SplsStore.locations(this).size + 1
            showPreview(lat, lon, SplsLocation.MIN_RADIUS, serial)
            SplsPrompts.askRadius(this, "Radius (meters)", SplsLocation.MIN_RADIUS, true, { r -> showPreview(lat, lon, r, serial) }, { radius ->
                val loc = SplsLocation(SplsStore.newId(), name, SplsLocation.MODE_CIRCLE, lat, lon, radius, false, System.currentTimeMillis())
                SplsStore.addLocation(this, loc)
                pick = false
                picked = false
                clearPreview()
                drawSplsCircles(true)
                startAfterPick(loc)
            }, { clearPreview() })
        })
    }

    private fun startAfterPick(loc: SplsLocation) {
        if (SplsSession.isOn()) {
            val running = SplsSession.active(this)?.name ?: "another record"
            note("Saved. The record \"$running\" is active, so recording did not start. Stop \"$running\" first, then press play on this record.")
            return
        }
        splsController.enable { ok ->
            if (ok) {
                SplsFlow.begin(this, loc, { note("Saved. Recording starts when you are inside this area.") }, { note("Saved. Recording did not start yet.") })
            } else {
                note("Saved. Recording did not start yet.")
            }
        }
    }

    // ------------------------------------------------------------ search

    private fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        if (!Geocoder.isPresent()) {
            Toast.makeText(this, "Place search is not available on this phone.", Toast.LENGTH_LONG).show()
            return
        }
        binding.searchProgress.visibility = View.VISIBLE
        Thread {
            @Suppress("DEPRECATION")
            val found = try {
                Geocoder(this, Locale.getDefault()).getFromLocationName(q, 1)?.firstOrNull()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                binding.searchProgress.visibility = View.GONE
                if (found == null) {
                    Toast.makeText(this, getString(R.string.no_internet) + " or place not found", Toast.LENGTH_LONG).show()
                } else if (::map.isInitialized) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(found.latitude, found.longitude), 15.0))
                    if (pick) pickPoint(found.latitude, found.longitude)
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        drawSplsCircles(true)
    }

    override fun onDestroy() {
        if (tracking) {
            try { locationClient.removeLocationUpdates(locationCallback) } catch (e: Exception) { }
        }
        super.onDestroy()
    }
}
