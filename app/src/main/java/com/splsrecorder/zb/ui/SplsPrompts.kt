package com.splsrecorder.zb.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.splsrecorder.zb.R
import com.splsrecorder.zb.spls.SplsLocation

/** Small reusable pop-ups for SPLS: a name, a radius, a choice from a list, a confirmation. */
object SplsPrompts {

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun field(ctx: Context, hint: String, text: String, numeric: Boolean): Pair<TextInputLayout, TextInputEditText> {
        val til = LayoutInflater.from(ctx).inflate(R.layout.dialog_value_field, null, false) as TextInputLayout
        til.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        til.hint = hint
        val et = til.findViewById<TextInputEditText>(R.id.value_edit)
        et.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        et.setText(text)
        et.setSelection(et.text?.length ?: 0)
        return Pair(til, et)
    }

    private fun wrap(ctx: Context, vararg views: android.view.View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 24), dp(ctx, 12), dp(ctx, 24), 0)
        for (v in views) addView(v)
    }

    /** Asks for a name. [taken] are names that exist already (compared without upper/lower case). */
    fun askName(ctx: Context, title: String, initial: String, taken: Collection<String>, onOk: (String) -> Unit, onCancel: (() -> Unit)? = null) {
        val (til, et) = field(ctx, "Name", initial, false)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(wrap(ctx, til))
            .setPositiveButton(R.string.dialog_set, null)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = et.text?.toString().orEmpty().trim()
                when {
                    name.isEmpty() -> til.error = "Enter a name"
                    name.length > 40 -> til.error = "Use at most 40 characters"
                    taken.any { it.equals(name, ignoreCase = true) } -> til.error = "A record with this name exists already"
                    else -> {
                        dialog.dismiss()
                        onOk(name)
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Asks for a radius in meters (at least 10). A smaller value is put back to 10 and the pop-up stays open.
     * [onLive] is called while typing, so a circle on the map can follow. [overMap] keeps the map visible.
     */
    fun askRadius(
        ctx: Context, title: String, initial: Double, overMap: Boolean,
        onLive: ((Double) -> Unit)?, onOk: (Double) -> Unit, onCancel: (() -> Unit)? = null
    ) {
        val (til, et) = field(ctx, "Radius (meters)", fmt(initial), true)
        val note = TextView(ctx).apply {
            text = "Minimum ${fmt(SplsLocation.MIN_RADIUS)} m, maximum ${fmt(SplsLocation.MAX_RADIUS)} m"
            textSize = 12.5f
            alpha = 0.7f
            setPadding(dp(ctx, 4), dp(ctx, 4), 0, 0)
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.replace(',', '.')?.toDoubleOrNull()
                if (v != null && v >= SplsLocation.MIN_RADIUS && v <= SplsLocation.MAX_RADIUS) onLive?.invoke(v)
            }
        })
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(wrap(ctx, til, note))
            .setPositiveButton(R.string.dialog_set, null)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .create()
        if (overMap) {
            dialog.window?.setDimAmount(0f)
            dialog.window?.setGravity(Gravity.BOTTOM)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = et.text?.toString().orEmpty().replace(',', '.').trim().toDoubleOrNull()
                when {
                    v == null || v < SplsLocation.MIN_RADIUS -> {
                        et.setText(fmt(SplsLocation.MIN_RADIUS))
                        et.setSelection(et.text?.length ?: 0)
                        til.error = "The radius cannot be smaller than ${fmt(SplsLocation.MIN_RADIUS)} m. It was set to ${fmt(SplsLocation.MIN_RADIUS)} m. Tap Set to confirm."
                    }
                    v > SplsLocation.MAX_RADIUS -> til.error = "The radius cannot be larger than ${fmt(SplsLocation.MAX_RADIUS)} m"
                    else -> {
                        dialog.dismiss()
                        onOk(v)
                    }
                }
            }
        }
        dialog.show()
    }

    fun fmt(d: Double): String = if (d == Math.rint(d)) d.toLong().toString() else d.toString()

    /** Choose one entry from a list, with a search field that filters the list while typing. */
    fun pickOne(ctx: Context, title: String, items: List<String>, emptyText: String, withSearch: Boolean, onPick: (Int) -> Unit) {
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage(emptyText).setPositiveButton("OK", null).show()
            return
        }
        val shown = ArrayList<Int>()
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, ArrayList<String>())
        fun filter(q: String) {
            adapter.clear()
            shown.clear()
            for ((i, t) in items.withIndex()) {
                if (q.isBlank() || t.contains(q.trim(), ignoreCase = true)) {
                    adapter.add(t)
                    shown.add(i)
                }
            }
            adapter.notifyDataSetChanged()
        }
        filter("")
        val list = ListView(ctx)
        list.adapter = adapter
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (withSearch) {
            val (til, et) = field(ctx, "Search", "", false)
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter(s?.toString().orEmpty()) }
            })
            box.addView(FrameLayout(ctx).apply {
                setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
                addView(til)
            })
        }
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 260)))
        val dialog = MaterialAlertDialogBuilder(ctx).setTitle(title).setView(box).setNegativeButton(R.string.dialog_cancel, null).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            onPick(shown[pos])
        }
        dialog.show()
    }

    fun confirm(ctx: Context, title: String, message: String, positive: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onOk() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
