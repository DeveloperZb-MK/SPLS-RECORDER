package com.splsrecorder.zb.spls

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.splsrecorder.zb.R
import com.splsrecorder.zb.utils.PrefKeys as K
import com.splsrecorder.zb.utils.PrefManager
import com.splsrecorder.zb.utils.SplsRow

/**
 * Records the real behaviour of the three providers (gps, network, fused) in the background.
 * Every recorded fix also gets its bearing, a jitter value and a drift value (see [onFix]).
 */
class SplsRecorderService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lm: LocationManager? = null
    private var fused: FusedLocationProviderClient? = null

    private var lastNetAcc = Double.NaN
    private var lastNetAt = 0L
    private var nmeaMsl = Double.NaN
    private var nmeaAt = 0L
    private var cachedLoc: SplsLocation? = null
    private var cachedAt = 0L
    private val lastAt = HashMap<String, Long>()
    private val lastSig = HashMap<String, String>()

    // jitter and drift: the previous fix and the last few fixes of each provider
    private class Pos(val lat: Double, val lon: Double, val at: Long)
    private val lastPos = HashMap<String, Pos>()
    private val recent = HashMap<String, ArrayDeque<Pos>>()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onFix(location) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            onFix(l)
        }
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ -> parseNmea(message) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SplsSession.isOn()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) begin()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "SPLS recording", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_spls_notif)
            .setContentTitle("SPLS Recorder is recording")
            .setContentText("Recording how the location providers behave on this phone")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
    }

    @SuppressLint("MissingPermission")
    private fun begin() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        running = true
        val t = HandlerThread("khpal-spls")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (manager.allProviders.contains(p)) manager.requestLocationUpdates(p, 2000L, 0f, listener, t.looper)
            } catch (e: Exception) {
            }
        }
        try {
            manager.addNmeaListener(nmeaListener, h)
        } catch (e: Exception) {
        }
        fused = LocationServices.getFusedLocationProviderClient(this)
        requestFused()
        // fused location can fall asleep: ask for it again regularly to wake it up
        h.postDelayed(object : Runnable {
            override fun run() {
                if (!running) return
                requestFused()
                h.postDelayed(this, 45_000L)
            }
        }, 45_000L)
    }

    @SuppressLint("MissingPermission")
    private fun requestFused() {
        val client = fused ?: return
        val t = thread ?: return
        try {
            client.removeLocationUpdates(fusedCallback)
            val req = LocationRequest()
            req.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            req.interval = 2000
            req.fastestInterval = 1000
            client.requestLocationUpdates(req, fusedCallback, t.looper)
        } catch (e: Exception) {
        }
    }

    private fun parseNmea(message: String) {
        // $xxGGA,time,lat,N,lon,E,quality,satellites,hdop,altitude(MSL),M,geoid,M,...
        if (message.length < 8 || message.substring(3, 6) != "GGA") return
        val f = message.split(',')
        if (f.size < 10) return
        val quality = f[6].toIntOrNull() ?: return
        if (quality <= 0) return
        val msl = f[9].toDoubleOrNull() ?: return
        nmeaMsl = msl
        nmeaAt = SystemClock.elapsedRealtime()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    private fun onFix(loc: Location) {
        // only real data: not while paused
        if (!SplsSession.isOn() || SplsSession.isPaused()) return
        val target = activeLocation() ?: return
        // area-limited recording: only inside the circle of the location
        if (target.isCircle && SplsGeo.distanceM(loc.latitude, loc.longitude, target.lat, target.lon) > target.radius) return
        if (loc.isFromMockProvider) return
        val provider = loc.provider ?: return
        if (provider != "gps" && provider != "network" && provider != "fused") return
        val now = SystemClock.elapsedRealtime()
        val h = loc.accuracy.toDouble()
        if (h <= 0.0) return

        val netAcc: Double
        if (provider == "network") {
            lastNetAcc = h
            lastNetAt = now
            netAcc = h
        } else {
            if (lastNetAcc.isNaN() || now - lastNetAt > 120_000L) return
            netAcc = lastNetAcc
        }

        val last = lastAt[provider] ?: 0L
        if (now - last < 3000L) return

        val vAcc = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null
        val alt = if (loc.hasAltitude()) loc.altitude else null
        val msl: Double? = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            !nmeaMsl.isNaN() && now - nmeaAt < 30_000L -> nmeaMsl
            else -> null
        }
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0

        val sig = "$h|$vAcc|$alt|$speed"
        if (sig == lastSig[provider] && now - last < 30_000L) return
        lastSig[provider] = sig
        lastAt[provider] = now

        // bearing: direction of travel in degrees from true north, empty when Android reports none
        val bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null
        val bearingAcc = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else null

        // jitter: meters from the previous fix of the same provider (only when that fix is less than a minute old)
        val prev = lastPos[provider]
        val gapOk = prev != null && now - prev.at < 60_000L
        val jitter = if (gapOk) SplsGeo.distanceM(prev!!.lat, prev.lon, loc.latitude, loc.longitude) else null

        // drift: meters from the average position of the last 20 fixes of the same provider (needs at least 3)
        val window = recent.getOrPut(provider) { ArrayDeque() }
        if (!gapOk) window.clear()
        val drift = if (window.size >= 3) {
            val mLat = window.sumOf { it.lat } / window.size
            val mLon = window.sumOf { it.lon } / window.size
            SplsGeo.distanceM(mLat, mLon, loc.latitude, loc.longitude)
        } else null
        val here = Pos(loc.latitude, loc.longitude, now)
        lastPos[provider] = here
        window.addLast(here)
        while (window.size > 20) window.removeFirst()

        SplsStore.append(
            applicationContext, target.id,
            SplsRow(System.currentTimeMillis(), provider, loc.latitude, loc.longitude, h, netAcc, vAcc, alt, msl, speed, bearing, bearingAcc, jitter, drift)
        )
    }

    /** The location that is recorded into (re-read every few seconds, so renames and radius changes apply). */
    private fun activeLocation(): SplsLocation? {
        val now = SystemClock.elapsedRealtime()
        val id = SplsSession.activeId() ?: return null
        val c = cachedLoc
        if (c != null && c.id == id && now - cachedAt < 5000L) return c
        cachedLoc = SplsStore.find(applicationContext, id)
        cachedAt = now
        return cachedLoc
    }

    override fun onDestroy() {
        running = false
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        try { lm?.removeNmeaListener(nmeaListener) } catch (e: Exception) { }
        try { fused?.removeLocationUpdates(fusedCallback) } catch (e: Exception) { }
        thread?.quitSafely()
        try { SplsStore.rebuild(applicationContext) } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "spls"
        private const val NOTIF_ID = 4711

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SplsRecorderService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SplsRecorderService::class.java))
        }
    }
}
