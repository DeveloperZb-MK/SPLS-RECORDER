package io.github.jqssun.gpssetter.ui

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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.splsrecorder.zb.R
import io.github.jqssun.gpssetter.spls.SplsAreas
import io.github.jqssun.gpssetter.spls.SplsLocation
import io.github.jqssun.gpssetter.spls.SplsSession
import io.github.jqssun.gpssetter.spls.SplsStore

/**
 * The record rows used in the SPLS pop-up (record list and deployment list) and in the map's SPLS Locations.
 * The number in front of a name is the number shown on the map marker. The active record is moved to the top.
 */
object SplsListUi {


    /** A scroll view that grows with its rows up to [maxPx] and then scrolls inside the pop-up. */
    class CappedScrollView(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
        private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var handingToParent = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    handingToParent = false
                    // Keep the outer popup from stealing the gesture until its direction is known.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(ev.x - downX)
                    val dy = ev.y - downY
                    if (kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > dx) {
                        // ScrollView uses +1 for scrolling down and -1 for scrolling up.
                        val direction = if (dy < 0f) 1 else -1
                        val innerCanScroll = canScrollVertically(direction)
                        handingToParent = !innerCanScroll
                        // At the relevant edge, release the gesture so the outer dialog scrolls.
                        parent?.requestDisallowInterceptTouchEvent(innerCanScroll)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    handingToParent = false
                }
            }
            // Once the inner list reaches its edge, do not claim the move; let the outer
            // ScrollView intercept it and continue scrolling the whole popup.
            if (handingToParent && ev.actionMasked == MotionEvent.ACTION_MOVE) return false
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

    class DeployHooks(
        val say: (String) -> Unit,
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
        val active = SplsRules.isActive(l) // running or paused: stop action remains available
        val running = SplsSession.isRecording(l.id)
        // Only the record currently receiving location fixes gets the active highlight.
        val line = rowContainer(ctx, running)
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
                }, null, { r -> SplsAreas.overlapMessage(ctx, l.lat, l.lon, r, l.id) })
            }
        }

        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(anchor, -card.measuredWidth + dpOf(ctx, 40), -dpOf(ctx, 40))
    }

    // ------------------------------------------------------------ deployment list: Activate / Deactivate

    fun fillDeploy(ctx: Context, layout: LinearLayout, query: String, h: DeployHooks) {
        layout.removeAllViews()
        val deployed = SplsRules.deployed(ctx)
        val list = numbered(ctx, query, deployed?.id)
        if (list.isEmpty()) {
            layout.addView(
                empty(
                    ctx,
                    if (query.isBlank()) "No learned records yet. Make a record first, then activate it here." else "No record matches your search."
                )
            )
            return
        }
        for ((serial, l) in list) {
            val on = deployed?.id == l.id
            val line = rowContainer(ctx, on)
            line.setPadding(dpOf(ctx, 12), dpOf(ctx, 8), dpOf(ctx, 8), dpOf(ctx, 8))
            line.addView(serialView(ctx, serial), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpOf(ctx, 6) })
            val complete = SplsRules.dataComplete(ctx, l.id)
            val sub = "${SplsStore.rowCount(ctx, l.id)} records - " + if (complete) "Complete" else "Incomplete"
            line.addView(nameAndSubtitle(ctx, l, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val button = (if (on) MaterialButton(ctx) else MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)).apply {
                text = if (on) "Deactivate" else "Activate"
                textSize = 12.5f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                setPadding(dpOf(ctx, 12), 0, dpOf(ctx, 12), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpOf(ctx, 36)).apply { marginStart = dpOf(ctx, 8) }
                setOnClickListener {
                    if (on) SplsActions.deactivate(ctx, l, h.say, h.changed)
                    else SplsActions.activate(ctx, l, h.say, h.changed)
                }
            }
            line.addView(button)
            layout.addView(line)
        }
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
