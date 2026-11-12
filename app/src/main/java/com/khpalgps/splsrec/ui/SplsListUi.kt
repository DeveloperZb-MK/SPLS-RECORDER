package com.khpalgps.splsrec.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.khpalgps.splsrec.R
import com.khpalgps.splsrec.spls.SplsLocation
import com.khpalgps.splsrec.spls.SplsSession
import com.khpalgps.splsrec.spls.SplsStore

/**
 * The record rows used in the SPLS pop-up.
 * The number in front of a name is the number shown on the map marker. The active record is moved to the top.
 */
object SplsListUi {


    /** A scroll view that grows with its rows up to [maxPx] and then scrolls inside the pop-up. */
    class CappedScrollView(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
            return super.onTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
            return super.onInterceptTouchEvent(ev)
        }
    }

    fun capped(ctx: Context, content: View, maxDp: Int): ScrollView =
        CappedScrollView(ctx, (maxDp * ctx.resources.displayMetrics.density).toInt()).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    class RecordHooks(
        val say: (String) -> Unit,
        val play: (SplsLocation) -> Unit,
        val stop: (SplsLocation) -> Unit,
        val changed: () -> Unit
    )

    private fun dpOf(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun onSurface(v: View) = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface)

    private fun rowBackground(ctx: Context, active: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpOf(ctx, 14).toFloat()
        setColor(if (active) 0x226750A4 else 0x12888888)
        if (active) setStroke(dpOf(ctx, 1), 0x666750A4)
    }

    private fun iconButton(ctx: Context, res: Int, desc: String, enabled: Boolean): ImageButton = ImageButton(ctx).apply {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setImageResource(res)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dpOf(ctx, 7), dpOf(ctx, 7), dpOf(ctx, 7), dpOf(ctx, 7))
        setColorFilter(onSurface(this))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
        layoutParams = LinearLayout.LayoutParams(dpOf(ctx, 36), dpOf(ctx, 36))
    }

    private fun nameAndSubtitle(ctx: Context, l: SplsLocation, subtitle: String): LinearLayout {
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = l.name
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(ctx).apply {
            tag = l.id
            text = subtitle
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dpOf(ctx, 2), 0, 0)
        })
        return texts
    }

    private fun serialView(ctx: Context, serial: Int): TextView = TextView(ctx).apply {
        text = "$serial."
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        minWidth = dpOf(ctx, 22)
    }

    private fun rowContainer(ctx: Context, active: Boolean): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpOf(ctx, 12), dpOf(ctx, 8), dpOf(ctx, 4), dpOf(ctx, 8))
        background = rowBackground(ctx, active)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpOf(ctx, 3)
            bottomMargin = dpOf(ctx, 3)
        }
    }

    private fun empty(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 13.5f
        alpha = 0.8f
        setPadding(dpOf(ctx, 8), dpOf(ctx, 12), dpOf(ctx, 8), dpOf(ctx, 12))
    }

    /** Serial = position in the stored list, which is the number on the map marker. */
    private fun numbered(ctx: Context, query: String, first: String?): List<Pair<Int, SplsLocation>> {
        val all = SplsStore.locations(ctx).mapIndexed { i, l -> Pair(i + 1, l) }
        val q = query.trim()
        val shown = if (q.isEmpty()) all else all.filter { it.second.name.contains(q, ignoreCase = true) }
        return shown.sortedBy { if (it.second.id == first) 0 else 1 }
    }

    // ------------------------------------------------------------ record list: delete, stop, play / pause, menu

    fun fillRecords(
        ctx: Context, layout: LinearLayout, query: String, h: RecordHooks,
        emptyText: String = "No SPLS records yet. Tap + to make a new record."
    ) {
        layout.removeAllViews()
        val activeId = if (SplsSession.isOn()) SplsSession.activeId() else null
        val list = numbered(ctx, query, activeId)
        if (list.isEmpty()) {
            layout.addView(
                empty(ctx, if (query.isBlank()) emptyText else "No record matches your search.")
            )
            return
        }
        for ((serial, l) in list) layout.addView(recordRow(ctx, serial, l, h))
    }

    private fun recordRow(ctx: Context, serial: Int, l: SplsLocation, h: RecordHooks): LinearLayout {
        val active = SplsRules.isActive(l)
        val running = active && !SplsSession.isPaused()
        val line = rowContainer(ctx, active)
        line.addView(serialView(ctx, serial), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpOf(ctx, 6) })
        line.addView(nameAndSubtitle(ctx, l, SplsRules.subtitle(ctx, l)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val delete = iconButton(ctx, R.drawable.ic_delete_24, "Delete record", true)
        delete.setOnClickListener {
            SplsPrompts.confirm(ctx, "Delete \"${l.name}\"?", "The record and all of its data are removed. This cannot be undone.", "Delete") {
                SplsStore.deleteLocation(ctx, l.id)
                h.say("Record deleted.")
                h.changed()
            }
        }
        line.addView(delete)

        val stop = iconButton(ctx, R.drawable.ic_stop, "Stop recording", active)
        stop.setOnClickListener { h.stop(l) }
        line.addView(stop)

        val play = iconButton(ctx, if (running) R.drawable.ic_spls_pause else R.drawable.ic_play, if (running) "Pause recording" else "Start or resume recording", true)
        play.setOnClickListener { h.play(l) }
        line.addView(play)

        val more = iconButton(ctx, R.drawable.ic_more_vert_24, "More", true)
        more.setOnClickListener { anchor -> showMenu(ctx, anchor, line, l, active, h) }
        line.addView(more)
        return line
    }

    /** Rename and Change radius (the radius only exists for records that record inside an area). */
    private fun showMenu(ctx: Context, anchor: View, line: View, l: SplsLocation, active: Boolean, h: RecordHooks) {
        val before = line.background
        line.background = rowBackground(ctx, true)
        val card = MaterialCardView(ctx).apply {
            radius = dpOf(ctx, 12).toFloat()
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            elevation = dpOf(ctx, 8).toFloat()
        }
        val items = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpOf(ctx, 8), 0, dpOf(ctx, 8))
        }
        card.addView(items)
        val popup = PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener { line.background = before }
        }

        fun item(icon: Int, label: String, onClick: () -> Unit) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpOf(ctx, 16), dpOf(ctx, 12), dpOf(ctx, 32), dpOf(ctx, 12))
                isClickable = true
                setOnClickListener { popup.dismiss(); onClick() }
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(icon)
                setColorFilter(onSurface(this))
                layoutParams = LinearLayout.LayoutParams(dpOf(ctx, 20), dpOf(ctx, 20)).apply { marginEnd = dpOf(ctx, 12) }
            })
            row.addView(TextView(ctx).apply { text = label; textSize = 14f; setTextColor(onSurface(this)) })
            items.addView(row)
        }

        item(android.R.drawable.ic_menu_edit, "Rename") {
            SplsPrompts.askName(ctx, "Rename record", l.name, SplsStore.locations(ctx).filter { it.id != l.id }.map { it.name }, { name ->
                SplsStore.find(ctx, l.id)?.let { SplsStore.update(ctx, it.copy(name = name)) }
                h.changed()
            })
        }
        if (l.isCircle) {
            item(android.R.drawable.ic_menu_mylocation, "Change radius") {
                SplsPrompts.askRadius(ctx, "Change radius", l.radius, false, null, { r ->
                    SplsStore.find(ctx, l.id)?.let { SplsStore.update(ctx, it.copy(radius = r)) }
                    h.changed()
                })
            }
        }

        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(anchor, -card.measuredWidth + dpOf(ctx, 40), -dpOf(ctx, 40))
    }

    /** Updates the text under each name in place (so a running recording shows its count without rebuilding the list). */
    fun refreshSubtitles(ctx: Context, layout: LinearLayout) {
        for (l in SplsStore.locations(ctx)) {
            layout.findViewWithTag<TextView>(l.id)?.let {
                val t = SplsRules.subtitle(ctx, l)
                if (it.text.toString() != t) it.text = t
            }
        }
    }
}
