package com.khpalgps.splsrec.ui

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.khpalgps.splsrec.R
import com.khpalgps.splsrec.spls.SplsRecorderService
import com.khpalgps.splsrec.spls.SplsSession
import com.khpalgps.splsrec.spls.SplsStore
import com.khpalgps.splsrec.utils.SplsBlocks

/** Pop-up of the Smart Provider Learning System: recording, the record list and the recorded data. */
object SplsDialog {

    private const val NOTICE_MS = 5000L
    private const val TICK_MS = 2000L

    /**
     * [onPickArea]: the user chose "Select another location", so the map must let the user pick an area.
     * [onChanged]: records were added, changed, started or stopped, so the map must redraw its circles.
     */
    fun show(ctx: Context, controller: SplsController, onPickArea: () -> Unit, onChanged: () -> Unit) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_spls, null)
        val sw = view.findViewById<MaterialSwitch>(R.id.spls_switch)
        val notice = view.findViewById<TextView>(R.id.spls_notice)
        val providers = view.findViewById<LinearLayout>(R.id.spls_providers)
        val search = view.findViewById<EditText>(R.id.spls_search)
        val dialog = MaterialAlertDialogBuilder(ctx).setView(view).create()
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var guard = false
        val expanded = HashSet<String>()
        var dataSignature = ""
        var refreshAll: () -> Unit = {}
        var redrawData: () -> Unit = {}

        // the list scrolls inside its card when it is long
        val listA = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        view.findViewById<FrameLayout>(R.id.spls_list_a_holder).addView(SplsListUi.capped(ctx, listA, 270))

        // one short message at a time, visible for 5 seconds, then it disappears
        val hide = Runnable { notice.visibility = View.GONE }
        fun say(msg: String) {
            notice.removeCallbacks(hide)
            notice.text = msg
            notice.visibility = View.VISIBLE
            notice.postDelayed(hide, NOTICE_MS)
        }

        fun cell(text: String, bold: Boolean = false, minDp: Int = 0): TextView = TextView(ctx).apply {
            this.text = text
            textSize = 12.5f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            if (minDp > 0) minWidth = dp(minDp)
        }

        fun statusIcon(ok: Boolean, size: Int): ImageView = ImageView(ctx).apply {
            setImageResource(if (ok) R.drawable.ic_status_ok else R.drawable.ic_status_bad)
            layoutParams = TableRow.LayoutParams(dp(size), dp(size)).also { it.gravity = Gravity.CENTER }
        }

        fun buildTable(provider: String, total: Int, bins: IntArray): View {
            val table = TableLayout(ctx)
            val head = TableRow(ctx)
            head.setBackgroundColor(Color.parseColor("#22888888"))
            for ((i, t) in listOf("Accuracy range", "Requirement", "Min. samples", "Recorded", "Status").withIndex()) {
                head.addView(cell(t, true, listOf(150, 100, 90, 80, 60)[i]))
            }
            table.addView(head)
            for (i in 0 until SplsBlocks.COUNT) {
                val min = SplsBlocks.minSamples(provider, i)
                val row = TableRow(ctx)
                if (i % 2 == 1) row.setBackgroundColor(Color.parseColor("#11888888"))
                row.addView(cell(SplsBlocks.LABELS[i]))
                row.addView(cell(if (min > 0) "Compulsory" else "Optional"))
                row.addView(cell(if (min > 0) min.toString() else "-"))
                row.addView(cell(bins[i].toString()))
                if (min > 0) row.addView(statusIcon(bins[i] >= min, 20)) else row.addView(cell("-"))
                table.addView(row)
            }
            val tr = TableRow(ctx)
            tr.setBackgroundColor(Color.parseColor("#22888888"))
            tr.addView(cell("Total records", true))
            tr.addView(cell("Compulsory", true))
            tr.addView(cell(SplsBlocks.MIN_TOTAL.toString(), true))
            tr.addView(cell(total.toString(), true))
            tr.addView(statusIcon(total >= SplsBlocks.MIN_TOTAL, 20))
            table.addView(tr)
            return HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = true
                addView(table)
            }
        }

        fun buildProvider(key: String, title: String, total: Int, bins: IntArray) {
            val complete = SplsBlocks.isComplete(key, total, bins)
            val open = expanded.contains(key)
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
            }
            
            // 1. Provider Title (takes up remaining space)
            header.addView(TextView(ctx).apply {
                text = title
                textSize = 15f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // 2. Record Count
            header.addView(TextView(ctx).apply {
                text = "$total of ${SplsBlocks.MIN_TOTAL}"
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            // 3. Status Icon (Check or Cross)
            header.addView(ImageView(ctx).apply {
                setImageResource(if (complete) R.drawable.ic_status_ok else R.drawable.ic_status_bad)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).also { 
                    it.marginStart = dp(12)
                    it.marginEnd = dp(4) 
                }
            })

            // 4. Expand/Collapse Chevron
            header.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_down)
                rotation = if (open) 180f else 0f
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })

            providers.addView(header)
            if (open) providers.addView(buildTable(key, total, bins))
            header.setOnClickListener {
                if (!expanded.add(key)) expanded.remove(key)
                // rebuilt by refresh() below
                header.post { redrawData() }
            }
            val line = View(ctx)
            line.setBackgroundColor(Color.parseColor("#22888888"))
            providers.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }

        // ------------------------------------------------------------ recorded data (follows the record that is recording)

        class Scope(val counts: SplsStore.Counts, val label: String)

        fun scope(): Scope {
            val recording = if (SplsSession.isOn()) SplsSession.active(ctx) else null
            return when {
                recording != null -> Scope(
                    SplsStore.countsOfLocation(ctx, recording.id),
                    "Showing: ${recording.name} (${if (SplsSession.isPaused()) "paused" else "recording"})"
                )
                else -> Scope(SplsStore.counts(ctx), "Showing: all records together")
            }
        }

        fun stateText(): String {
            val on = SplsSession.isOn()
            val active = SplsSession.active(ctx)
            val paused = SplsSession.isPaused()
            val where = if (active != null) " - ${active.name} (${SplsFlow.describe(active)})" else ""
            return when {
                !on -> "Recording: off"
                paused -> "Recording: paused$where"
                SplsRecorderService.running -> "Recording: running$where"
                else -> "Recording: starting$where"
            }
        }

        fun refreshData(force: Boolean) {
            val sc = scope()
            val c = sc.counts
            val state = stateText()
            view.findViewById<TextView>(R.id.spls_state).text = state
            val sig = "${sc.label}|${c.gps}|${c.network}|${c.fused}|${c.bins.values.joinToString { it.joinToString(",") }}"
            if (!force && sig == dataSignature) return
            dataSignature = sig
            providers.removeAllViews()
            buildProvider("network", "Network records", c.network, c.bins.getValue("network"))
            buildProvider("gps", "GPS records", c.gps, c.bins.getValue("gps"))
            buildProvider("fused", "Fused records", c.fused, c.bins.getValue("fused"))
            view.findViewById<TextView>(R.id.spls_scope).text = sc.label
        }

        redrawData = { refreshData(true) }

        // ------------------------------------------------------------ lists

        val recordHooks = SplsListUi.RecordHooks(
            say = { say(it) },
            play = { l ->
                SplsActions.playOrPause(
                    ctx, l, { say(it) },
                    startNew = { loc ->
                        controller.enable { ok ->
                            if (ok) {
                                SplsFlow.begin(ctx, loc, { if (dialog.isShowing) view.post { refreshAll() } }, { if (dialog.isShowing) view.post { refreshAll() } })
                            } else if (dialog.isShowing) {
                                view.post { refreshAll() }
                            }
                        }
                    },
                    changed = { refreshAll() }
                )
            },
            stop = { l -> SplsActions.stop(ctx, l, { say(it) }, { refreshAll() }) },
            changed = { refreshAll() }
        )
        fun refreshLists() {
            val q = search.text?.toString().orEmpty()
            SplsListUi.fillRecords(ctx, listA, q, recordHooks)
            guard = true
            sw.isChecked = SplsSession.isOn()
            guard = false
        }

        fun refreshExport() {
            view.findViewById<View>(R.id.spls_export).isEnabled = SplsStore.locations(ctx).any { SplsRules.canExport(ctx, it) }
        }

        refreshAll = {
            refreshLists()
            refreshData(true)
            refreshExport()
            onChanged()
        }

        // numbers and counts follow a running recording without rebuilding the lists
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                refreshData(false)
                SplsListUi.refreshSubtitles(ctx, listA)
                refreshExport()
                view.postDelayed(this, TICK_MS)
            }
        }

        controller.notifier = { msg -> say(msg) }
        dialog.setOnDismissListener {
            controller.notifier = null
            notice.removeCallbacks(hide)
            view.removeCallbacks(tick)
        }

        fun leaveForMap() {
            dialog.dismiss()
            onPickArea()
        }

        // ------------------------------------------------------------ switches and buttons

        sw.setOnCheckedChangeListener { _, checked ->
            if (guard) return@setOnCheckedChangeListener
            if (checked) {
                controller.enable { ok ->
                    if (!ok) {
                        if (dialog.isShowing) view.post { refreshAll() }
                    } else {
                        SplsFlow.chooseStart(
                            ctx, { msg -> say(msg) },
                            done = { if (dialog.isShowing) view.post { refreshAll() } },
                            canceled = { if (dialog.isShowing) view.post { refreshAll() } },
                            leave = { leaveForMap() }
                        )
                    }
                }
            } else {
                SplsSession.stopForNow(ctx)
                say("Recording stopped. Start a record again with its play button.")
                view.post { refreshAll() }
            }
        }

        view.findViewById<View>(R.id.spls_add).setOnClickListener {
            if (SplsSession.isOn()) {
                SplsRules.warnOtherRecordActive(ctx, null)
                return@setOnClickListener
            }
            controller.enable { ok ->
                if (ok) {
                    SplsFlow.newRecording(
                        ctx, { msg -> say(msg) },
                        done = { if (dialog.isShowing) view.post { refreshAll() } },
                        canceled = { if (dialog.isShowing) view.post { refreshAll() } },
                        leave = { leaveForMap() }
                    )
                } else if (dialog.isShowing) {
                    view.post { refreshAll() }
                }
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshLists() }
        })

        view.findViewById<View>(R.id.spls_import).setOnClickListener { controller.import { if (dialog.isShowing) refreshAll() } }
        view.findViewById<View>(R.id.spls_export).setOnClickListener {
            val ready = SplsStore.locations(ctx).filter { SplsRules.canExport(ctx, it) }
            if (ready.isEmpty()) {
                say("Nothing to export yet. A record needs all compulsory blocks complete, and it must be paused or stopped.")
            } else if (ready.size == 1) {
                controller.exportLocation(ready[0])
            } else {
                SplsPrompts.pickOne(ctx, "Select a record to export", ready.map { it.name }, "There is no record that can be exported.", false) { i ->
                    controller.exportLocation(ready[i])
                }
            }
        }
        view.findViewById<View>(R.id.spls_close).setOnClickListener { dialog.dismiss() }

        // if SPLS is on but the recorder is not running (for example after a restart), start it again
        if (SplsSession.isOn() && !SplsRecorderService.running) {
            try { SplsRecorderService.start(ctx.applicationContext) } catch (e: Exception) { }
        }
        refreshAll()
        dialog.show()
        view.postDelayed(tick, TICK_MS)
    }
}
