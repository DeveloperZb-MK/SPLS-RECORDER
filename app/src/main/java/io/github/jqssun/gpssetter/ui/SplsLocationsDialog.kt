package io.github.jqssun.gpssetter.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.R

/**
 * "SPLS Locations" on the map: the same record list as in the SPLS pop-up
 * (delete, stop, play / pause, menu with Rename and Change radius), active record on top.
 */
object SplsLocationsDialog {

    private const val NOTICE_MS = 5000L

    fun show(ctx: Context, onChanged: () -> Unit = {}) {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val notice = TextView(ctx).apply {
            setBackgroundColor(0xFFFFF3E0.toInt())
            setTextColor(0xFF5D4037.toInt())
            textSize = 13.5f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        container.addView(notice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        val search = EditText(ctx).apply {
            hint = "Search records"
            textSize = 15f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setBackgroundResource(R.drawable.bg_spls_search)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_spls_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(currentHintTextColor)
            setPadding(dp(14), 0, dp(14), 0)
        }
        container.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("SPLS Locations")
            .setView(ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("Close", null)
            .create()

        val hide = Runnable { notice.visibility = View.GONE }
        fun say(msg: String) {
            notice.removeCallbacks(hide)
            notice.text = msg
            notice.visibility = View.VISIBLE
            notice.postDelayed(hide, NOTICE_MS)
        }

        var rebuild: () -> Unit = {}
        val hooks = SplsListUi.RecordHooks(
            say = { say(it) },
            play = { l ->
                SplsActions.playOrPause(
                    ctx, l, { say(it) },
                    startNew = { loc ->
                        val problem = SplsRules.permissionProblem(ctx)
                        if (problem != null) {
                            say(problem)
                        } else {
                            SplsFlow.begin(ctx, loc, { rebuild(); onChanged() }, { rebuild() })
                        }
                    },
                    changed = { rebuild(); onChanged() }
                )
            },
            stop = { l -> SplsActions.stop(ctx, l, { say(it) }, { rebuild(); onChanged() }) },
            changed = { rebuild(); onChanged() }
        )
        rebuild = {
            SplsListUi.fillRecords(
                ctx, list, search.text?.toString().orEmpty(), hooks,
                "No SPLS records yet. Start a recording in Settings, Smart provider learning system, and it appears here."
            )
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { rebuild() }
        })

        dialog.setOnDismissListener { notice.removeCallbacks(hide) }
        rebuild()
        dialog.show()
    }
}
