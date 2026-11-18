package com.splsrecorder.zb.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.splsrecorder.zb.R
import com.splsrecorder.zb.utils.PrefKeys as K
import com.splsrecorder.zb.utils.PrefManager

/**
 * The start checklist: before a recording starts, network, GPS and fused must each give a real position.
 * Each row gets a tick when its provider delivers a fix. Resume flows can require a final Continue confirmation.
 * A provider that is silent for about 20 seconds shows a hint what to do. Cancel always works.
 */
object SplsChecklist {

    private const val HINT_MS = 20_000L
    private const val READY_DELAY_MS = 700L

    private fun hint(p: String): String = when (p) {
        "gps" -> "No GPS signal yet. Move outdoors with a clear view of the sky."
        "network" -> "No network location. Turn on Wi-Fi and mobile data and Location."
        else -> "No fused location. Check that Location is on and Google Play services (if used) are working."
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, onReady: () -> Unit, onCancel: () -> Unit, confirmReady: Boolean = false) {
        fun showReadyConfirmation() {
            if (PrefManager.getRaw(K.SPLS_READY_MESSAGE_HIDDEN) == "true") {
                onReady()
                return
            }
            var completed = false
            val density = ctx.resources.displayMetrics.density
            val sideInset = (24 * density).toInt()
            val remember = CheckBox(ctx).apply {
                text = "Don't show this message again"
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                // Inset is applied as margin (not padding) so the box lines up with the message text;
                // a small start padding gives a tidy gap between the box and its label.
                setPadding((8 * density).toInt(), (8 * density).toInt(), 0, (8 * density).toInt())
                minHeight = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = sideInset - (2 * density).toInt()
                    marginEnd = sideInset
                }
            }
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = "Network, GPS and Fused are all connected and confirmed. Move to the place where you want to record, then tap Continue.\n\n" +
                        "Please continue soon: after a short while, your phone will forget this confirmation and you will need to repeat the check.\n\n" +
                        "You must repeat this check before resuming a paused or stopped recording."
                    textSize = 16f
                    setPadding(sideInset, (8 * density).toInt(), sideInset, 0)
                })
                addView(remember)
            }
            val readyDialog = MaterialAlertDialogBuilder(ctx)
                .setTitle("Your location sources are ready")
                .setView(content)
                .setNegativeButton("Cancel") { _, _ ->
                    if (!completed) {
                        completed = true
                        onCancel()
                    }
                }
                .setPositiveButton("Continue") { _, _ ->
                    if (!completed) {
                        completed = true
                        if (remember.isChecked) PrefManager.setRaw(K.SPLS_READY_MESSAGE_HIDDEN, "true")
                        onReady()
                    }
                }
                .setOnCancelListener {
                    if (!completed) {
                        completed = true
                        onCancel()
                    }
                }
                .create()
            readyDialog.setCanceledOnTouchOutside(false)
            readyDialog.show()
        }

        val app = ctx.applicationContext
        // without the location permission nothing can be checked here; the normal permission steps handle that
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (confirmReady) onCancel() else onReady()
            return
        }
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val main = Handler(Looper.getMainLooper())

        val ticked = HashMap<String, Boolean>()
        val icons = HashMap<String, ImageView>()
        val hints = HashMap<String, TextView>()
        for (p in listOf("network", "gps", "fused")) ticked[p] = false

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        column.addView(TextView(ctx).apply {
            text = if (confirmReady) "All three providers must show a tick before you can continue." else "Recording starts by itself when all three providers have a tick."
            textSize = 13f
            alpha = 0.8f
            setPadding(0, 0, 0, dp(8))
        })
        for (p in listOf("network", "gps", "fused")) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val icon = ImageView(ctx).apply { setImageResource(R.drawable.ic_status_bad) }
            line.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            line.addView(TextView(ctx).apply {
                text = when (p) { "network" -> "Network"; "gps" -> "GPS"; else -> "Fused" }
                textSize = 16f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            column.addView(line)
            val h = TextView(ctx).apply {
                text = hint(p)
                textSize = 12.5f
                alpha = 0.8f
                visibility = android.view.View.GONE
                setPadding(dp(36), dp(2), 0, 0)
            }
            column.addView(h)
            icons[p] = icon
            hints[p] = h
        }

        var done = false
        var lm: LocationManager? = null
        var fused: FusedLocationProviderClient? = null
        var dialogRef: androidx.appcompat.app.AlertDialog? = null

        val lmListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.isFromMockProvider) return
                val p = location.provider ?: return
                if (p == "gps" || p == "network") main.post { mark(p, ticked, icons, hints, main, { done }, {
                    done = true
                    dialogRef?.dismiss()
                    if (confirmReady) showReadyConfirmation() else onReady()
                }) }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val l = result.lastLocation ?: return
                if (l.isFromMockProvider) return
                main.post { mark("fused", ticked, icons, hints, main, { done }, {
                    done = true
                    dialogRef?.dismiss()
                    if (confirmReady) showReadyConfirmation() else onReady()
                }) }
            }
        }

        fun cleanup() {
            main.removeCallbacksAndMessages(null)
            try { lm?.removeUpdates(lmListener) } catch (e: Exception) { }
            try { fused?.removeLocationUpdates(fusedCallback) } catch (e: Exception) { }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Before recording starts")
            .setView(column)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialogRef = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            cleanup()
            if (!done) {
                done = true
                onCancel()
            }
        }
        dialog.show()

        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (manager.allProviders.contains(p)) manager.requestLocationUpdates(p, 1000L, 0f, lmListener, Looper.getMainLooper())
            } catch (e: Exception) {
            }
        }
        try {
            val client = LocationServices.getFusedLocationProviderClient(app)
            fused = client
            val req = LocationRequest()
            req.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            req.interval = 1000
            req.fastestInterval = 1000
            client.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
        } catch (e: Exception) {
        }

        main.postDelayed({
            for (p in listOf("network", "gps", "fused")) if (ticked[p] != true) hints[p]?.visibility = android.view.View.VISIBLE
        }, HINT_MS)
    }

    /** Puts a tick on the row. When all three rows have a tick, [ready] runs after a short moment so the ticks can be seen. */
    private fun mark(
        p: String, ticked: HashMap<String, Boolean>, icons: HashMap<String, ImageView>, hints: HashMap<String, TextView>,
        main: Handler, isDone: () -> Boolean, ready: () -> Unit
    ) {
        if (isDone() || ticked[p] == true) return
        ticked[p] = true
        icons[p]?.setImageResource(R.drawable.ic_status_ok)
        hints[p]?.visibility = android.view.View.GONE
        if (ticked.values.all { it }) main.postDelayed({ if (!isDone()) ready() }, READY_DELAY_MS)
    }
}
