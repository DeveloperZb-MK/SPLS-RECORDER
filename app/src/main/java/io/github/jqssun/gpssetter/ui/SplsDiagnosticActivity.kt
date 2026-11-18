package io.github.jqssun.gpssetter.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.jqssun.gpssetter.spls.SplsDiagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only view of on-device SPLS events and health checks. */
class SplsDiagnosticActivity : AppCompatActivity() {
    private lateinit var report: TextView

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(SplsDiagnostics.export(this)) }
        } catch (e: Exception) {
            report.text = "Export failed: ${e.message ?: e.javaClass.simpleName}\n\n" + report.text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)) }
        val toolbar = MaterialToolbar(this).apply {
            title = "SPLS Diagnostic Logger"
            navigationIcon = createBackArrowDrawable()
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        val intro = TextView(this).apply {
            text = "Read-only runtime diagnostics. The logger does not change location records, rebuilding, or deployment."
            textSize = 14f; setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(intro)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val refresh = MaterialButton(this).apply {
            text = "Refresh"
            minWidth = 0
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            textSize = 12f
            maxLines = 2
            setOnClickListener { refreshReport() }
        }
        val export = MaterialButton(this).apply {
            text = "Export\ndiagnostics"
            minWidth = 0
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            textSize = 12f
            maxLines = 2
            setOnClickListener {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                exportLauncher.launch("KHPAL-SPLS-diagnostics-$stamp.txt")
            }
        }
        val clear = MaterialButton(this).apply {
            text = "Clear history"
            minWidth = 0
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            textSize = 12f
            maxLines = 2
            setOnClickListener {
                AlertDialog.Builder(this@SplsDiagnosticActivity)
                    .setTitle("Clear diagnostic history?")
                    .setMessage("This permanently clears SPLS diagnostic event history on this device. Self-checks, SPLS location records, and deployed models will not be deleted.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        if (SplsDiagnostics.clearEvents(this@SplsDiagnosticActivity)) {
                            refreshReport()
                            Toast.makeText(this@SplsDiagnosticActivity, "Diagnostic history cleared", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@SplsDiagnosticActivity, "Could not clear diagnostic history", Toast.LENGTH_LONG).show()
                        }
                    }
                    .show()
            }
        }
        actionRow.addView(refresh, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        actionRow.addView(export, LinearLayout.LayoutParams(0, dp(52), 1f))
        buttons.addView(actionRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        buttons.addView(clear, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        root.addView(buttons)
        val scroll = ScrollView(this)
        report = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, dp(14), 0, dp(24)) }
        scroll.addView(report)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshReport()
    }

    override fun onResume() { super.onResume(); if (::report.isInitialized) refreshReport() }

    private fun refreshReport() {
        val checks = SplsDiagnostics.runChecks(this)
        report.text = buildString {
            appendLine("SELF-CHECKS")
            appendLine("═══════════")
            checks.forEach { appendLine("${it.status}  ${it.title}"); appendLine("  ${it.detail}"); appendLine() }
            appendLine("EVENT HISTORY — newest first")
            appendLine("════════════════════════════")
            val history = SplsDiagnostics.events(this@SplsDiagnosticActivity)
            if (history.isEmpty()) appendLine("No events recorded yet. Events appear as SPLS recording and rebuilding occur.")
            else history.forEach { appendLine(it); appendLine() }
        }
    }

    private fun createBackArrowDrawable(): Drawable {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        return object : Drawable() {
            override fun draw(canvas: Canvas) {
                val b = bounds
                val midY = b.exactCenterY()
                val left = b.left.toFloat() + dp(5).toFloat()
                val right = b.right.toFloat() - dp(5).toFloat()
                val path = Path().apply {
                    moveTo(right, midY)
                    lineTo(left, midY)
                    moveTo(left, midY)
                    lineTo(left + dp(8).toFloat(), midY - dp(8).toFloat())
                    moveTo(left, midY)
                    lineTo(left + dp(8).toFloat(), midY + dp(8).toFloat())
                }
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Android")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth(): Int = dp(24)
            override fun getIntrinsicHeight(): Int = dp(24)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
