package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.os.Looper
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.splsrecorder.zb.R
import io.github.jqssun.gpssetter.spls.SplsSession
import io.github.jqssun.gpssetter.spls.SplsStore
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.SupportMapFragment
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import io.github.jqssun.gpssetter.spls.SplsGeo
import android.view.Gravity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private lateinit var locationClient: FusedLocationProviderClient

    // Lists to hold ALL circles and markers
    private val splsPolygons = mutableListOf<Polygon>()
    private val splsMarkers = mutableListOf<Marker>()

    // Variables for temporary preview circle
    private var tempPolygon: Polygon? = null
    private var tempMarker: Marker? = null

    override fun hasMarker(): Boolean {
        if (mMarker != null) {
            return true
        }
        return false
    }

    private fun updateMarker(it: LatLng) {
        if (mMarker == null) {
            mMarker = mMap.addMarker(MarkerOptions().position(it))
        } else {
            mMarker?.position = it!!
        }
    }

    private fun removeMarker() {
        mMarker?.remove() 
        mMarker = null
    }

    override fun initializeMap() {
        MapLibre.getInstance(this)
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction().replace(R.id.map, mapFragment).commit()
        mapFragment?.getMapAsync(this)
    }

    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(latLng!!).zoom(12.0f.toDouble()).bearing(0f.toDouble()).tilt(0f.toDouble()).build()
                ))
                mMarker?.apply { position = latLng }
            }
        }
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        with(mMap){
            applyStyle(satMaxZoom, true)
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.setCompassEnabled(true)
            uiSettings.setCompassMargins(0,480,120,0)
            uiSettings.setLogoEnabled(true)
            uiSettings.setLogoGravity(Gravity.BOTTOM or Gravity.START)
            uiSettings.setLogoMargins(dp(16), 0, 0, dp(48))
            uiSettings.setAttributionEnabled(true)
            uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.END)
            uiSettings.setAttributionMargins(0, 0, dp(16), dp(48))

            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom.toDouble()))
            }

            addOnMapClickListener(this@MapActivity)
            addOnCameraIdleListener { onCameraIdle() }
        }
        
        startLocationTracking()
        drawAllSplsCircles()
    }

    private fun startLocationTracking() {
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                // Redraw all circles on every location update to hide the active one if we are inside
                drawAllSplsCircles(loc)
            }
        }
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var satMaxZoom = MapStyles.DEFAULT_SAT_MAX_ZOOM
    private var lastProbeKey: String? = null
    private var probeJob: Job? = null

    private fun applyStyle(maxZoom: Int, firstTime: Boolean) {
        val json = MapStyles.styleJson(PrefManager.mapType, maxZoom)
        mMap.setStyle(Style.Builder().fromJson(json)) { style ->
            activateLocationDot(style, firstTime)
            // MapLibre wipes markers when style changes, so we must redraw them
            drawAllSplsCircles()
        }
    }

    @SuppressLint("MissingPermission")
    private fun activateLocationDot(style: Style, firstTime: Boolean) {
        if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationComponent = mMap.locationComponent
            locationComponent.activateLocationComponent(LocationComponentActivationOptions.builder(this@MapActivity, style).useDefaultLocationEngine(true).build())
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = if (firstTime) CameraMode.TRACKING else CameraMode.NONE
            locationComponent.renderMode = RenderMode.COMPASS
        } else if (firstTime) {
            ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99)
        }
    }

    private fun onCameraIdle() {
        if (!MapStyles.isEsri(viewModel.mapType)) return
        val pos = mMap.cameraPosition
        if (pos.zoom < 14.0) return
        val target = pos.target ?: return
        val key = EsriProbe.cacheKey(target.latitude, target.longitude)
        if (key == lastProbeKey) return
        lastProbeKey = key
        probeJob?.cancel()
        probeJob = lifecycleScope.launch {
            val z = withContext(Dispatchers.IO) { EsriProbe.realMaxZoom(target.latitude, target.longitude) }
            if (z != satMaxZoom) {
                satMaxZoom = z
                applyStyle(z, false)
            }
        }
    }

    private fun getSplsMarkerIcon(serial: Int): org.maplibre.android.annotations.Icon {
        val bitmap = SplsMarker.bitmap(this, serial)
        return IconFactory.getInstance(this).fromBitmap(bitmap)
    }

    private var lastRealFix: Location? = null

    override fun drawAllSplsCircles(currentLoc: Location?) {
        if (!::mMap.isInitialized) return
        
        // Clear previous permanent circles
        splsPolygons.forEach { mMap.removePolygon(it) }
        splsPolygons.clear()
        splsMarkers.forEach { it.remove() }
        splsMarkers.clear()

        if (currentLoc != null) lastRealFix = currentLoc
        val here = lastRealFix
        val locations = SplsStore.locations(this)

        locations.forEachIndexed { index, l ->
            if (l.isCircle) {
                // while you are inside a recorded / recording circle, that circle and its marker are hidden
                var shouldDraw = true
                if (here != null) {
                    val dist = FloatArray(1)
                    Location.distanceBetween(here.latitude, here.longitude, l.lat, l.lon, dist)
                    if (dist[0] <= l.radius) shouldDraw = false
                }

                if (shouldDraw) {
                    val points = SplsGeo.circle(l.lat, l.lon, l.radius).map { LatLng(it.first, it.second) }
                    splsPolygons.add(mMap.addPolygon(PolygonOptions().addAll(points).fillColor(0x336750A4).strokeColor(0xFF6750A4.toInt())))
                    splsMarkers.add(mMap.addMarker(MarkerOptions().position(LatLng(l.lat, l.lon)).icon(getSplsMarkerIcon(index + 1))))
                }
            }
        }
    }

    override fun showTemporarySplsCircle(lat: Double, lon: Double, radiusM: Double, serial: Int) {
        if (!::mMap.isInitialized) return
        tempPolygon?.let { mMap.removePolygon(it) }
        tempMarker?.remove()

        val points = SplsGeo.circle(lat, lon, radiusM).map { LatLng(it.first, it.second) }
        tempPolygon = mMap.addPolygon(PolygonOptions().addAll(points).fillColor(0x336750A4).strokeColor(0xFF6750A4.toInt()))
        tempMarker = mMap.addMarker(MarkerOptions().position(LatLng(lat, lon)).icon(getSplsMarkerIcon(serial)))
    }

    override fun clearTemporarySplsCircle() {
        if (!::mMap.isInitialized) return
        tempPolygon?.let { mMap.removePolygon(it) }
        tempPolygon = null
        tempMarker?.remove()
        tempMarker = null
    }

    override fun zoomMap(zoomIn: Boolean) {
        if (!::mMap.isInitialized) return
        mMap.animateCamera(if (zoomIn) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
    }

    override fun onMapClick(latLng: LatLng): Boolean {
        markSplsPoint()
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
            }
        }
        return true
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.contentDescription = "Change map style"
        if (PrefManager.mapType !in 1..4) PrefManager.mapType = 2
        binding.addfavorite.setIconResource(R.drawable.ic_map_type)
        binding.addfavorite.setOnClickListener {
            PrefManager.mapType = when (PrefManager.mapType) {
                2 -> 1
                1 -> 3
                3 -> 4
                else -> 2
            }
            lastProbeKey = null
            applyStyle(satMaxZoom, false)
        }
        binding.toolbar.visibility = View.GONE
        binding.navView.visibility = View.GONE
        binding.getlocation.setOnClickListener { getLastLocation() }
    }
}
