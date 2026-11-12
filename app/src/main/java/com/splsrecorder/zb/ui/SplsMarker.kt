package com.splsrecorder.zb.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.splsrecorder.zb.R

/** The picture of an SPLS marker on the map: a small brain (18dp) with its number beside it, readable on any map. */
object SplsMarker {

    fun bitmap(ctx: Context, serial: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val glyph = 18f * d                    // the brain itself is 18dp wide
        val box = glyph * 24f / 19f            // the vector is drawn in 24 units and the brain spans about 19 of them
        val padSide = 12f * d
        val padTop = 7f * d
        val bmp = Bitmap.createBitmap((box + 2 * padSide).toInt(), (box + padTop).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val brain = ContextCompat.getDrawable(ctx, R.drawable.ic_pref_brain_small)!!.mutate()
        brain.setBounds(padSide.toInt(), padTop.toInt(), (padSide + box).toInt(), (padTop + box).toInt())
        brain.draw(canvas)

        if (serial > 0) {
            val text = serial.toString()
            val size = 11f * d
            val x = padSide + box * 0.86f
            val y = size + 1f * d
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2.6f * d
                strokeJoin = Paint.Join.ROUND
                textSize = size
                typeface = Typeface.DEFAULT_BOLD
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                textSize = size
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(text, x, y, outline)
            canvas.drawText(text, x, y, fill)
        }
        return bmp
    }
}
