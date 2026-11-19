package io.github.jqssun.gpssetter.utils

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.controlwear.virtual.joystick.android.JoystickView
import kotlin.math.cos
import kotlin.math.sin

class JoystickService : Service() {

    private var windowManager: WindowManager? = null
    private var joystickContainer: FrameLayout? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val container = FrameLayout(this)
        val joystick = JoystickView(this)
        container.addView(
            joystick,
            FrameLayout.LayoutParams(dp(100), dp(100), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(20)
                marginStart = dp(20)
            }
        )
        joystickContainer = container

        joystick.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                runCatching { updateLocation(PrefManager.getLat, PrefManager.getLng) }
            }
            false
        }
        joystick.setOnMoveListener { angle, strength, _ ->
            val radians = Math.toRadians(angle.toDouble())
            runCatching {
                val factorX = cos(radians) / 100000.0 * (strength / 30)
                val factorY = sin(radians) / 100000.0 * (strength / 30)
                updateLocation(PrefManager.getLat + factorY, PrefManager.getLng + factorX)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.LEFT }
        windowManager?.addView(container, params)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        joystickContainer?.let { windowManager?.removeView(it) }
        joystickContainer = null
        super.onDestroy()
    }

    private fun updateLocation(lat: Double, lon: Double) {
        PrefManager.update(start = PrefManager.isStarted, la = lat, ln = lon)
    }
}
