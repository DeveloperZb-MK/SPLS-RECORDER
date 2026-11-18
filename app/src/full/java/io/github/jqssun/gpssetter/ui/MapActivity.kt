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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.spls.SplsSession
import io.github.jqssun.gpssetter.spls.SplsStore
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private lateinit var locationClient: FusedLocationProviderClient

    // Lists to hold ALL circles and markers
    private val splsCircles = mutableListOf<Circle>()
    private val splsMarkers = mutableListOf<Marker>()

    // Variables for temporary preview circle
    private var tempCircle: Circle? = null
    private var tempMarker: Marker? = null

    override fun hasMarker(): Boolean {
        if (!mMarker?.isVisible!!) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
    }
    private fun removeMarker() {
        mMarker?.isVisible = false
    }
    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction().replace(R.id.map, mapFragment).commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(latLng!!).zoom(12.0f).bearing(0f).tilt(0f).build()
                ))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }
    }
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap){
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                setMyLocationEnabled(true); 
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
            }
            setTrafficEnabled(true)
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0,80,0,0)
            mapType = viewModel.mapType

            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false))
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }
            setOnMapClickListener(this@MapActivity)
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

    private fun getSplsMarkerIcon(serial: Int): BitmapDescriptor {
        val bitmap = SplsMarker.bitmap(this, serial)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private var lastRealFix: Location? = null

    override fun drawAllSplsCircles(currentLoc: Location?) {
        if (!::mMap.isInitialized) return
        
        // Clear previous permanent circles
        splsCircles.forEach { it.remove() }
        splsCircles.clear()
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
                    splsCircles.add(mMap.addCircle(CircleOptions().center(LatLng(l.lat, l.lon)).radius(l.radius).strokeColor(0xFF6750A4.toInt()).strokeWidth(3f).fillColor(0x336750A4)))
                    mMap.addMarker(MarkerOptions().position(LatLng(l.lat, l.lon)).icon(getSplsMarkerIcon(index + 1)))?.let { splsMarkers.add(it) }
                }
            }
        }
    }

    override fun showTemporarySplsCircle(lat: Double, lon: Double, radiusM: Double, serial: Int) {
        if (!::mMap.isInitialized) return
        tempCircle?.remove()
        tempMarker?.remove()

        tempCircle = mMap.addCircle(CircleOptions().center(LatLng(lat, lon)).radius(radiusM).strokeColor(0xFF6750A4.toInt()).strokeWidth(3f).fillColor(0x336750A4))
        tempMarker = mMap.addMarker(MarkerOptions().position(LatLng(lat, lon)).icon(getSplsMarkerIcon(serial)))
    }

    override fun clearTemporarySplsCircle() {
        if (!::mMap.isInitialized) return
        tempCircle?.remove()
        tempCircle = null
        tempMarker?.remove()
        tempMarker = null
    }

    override fun zoomMap(zoomIn: Boolean) {
        if (!::mMap.isInitialized) return
        mMap.animateCamera(if (zoomIn) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
    }

    override fun onMapClick(latLng: LatLng) {
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
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener { addFavoriteDialog() }
        binding.getlocation.setOnClickListener { getLastLocation() }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            if (SplsGuard.blockMock(this@MapActivity)) return@setOnClickListener
            viewModel.update(true, lat, lon)
            mLatLng.let { updateMarker(it!!) }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect{ value -> showStartNotification(value) }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            mLatLng.let { viewModel.update(false, it!!.latitude, it.longitude) }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}
