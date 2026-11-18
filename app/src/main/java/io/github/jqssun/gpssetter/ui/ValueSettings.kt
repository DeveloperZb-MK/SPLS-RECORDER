package io.github.jqssun.gpssetter.ui

import android.content.Context
import android.text.InputFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.splsrecorder.zb.R
import io.github.jqssun.gpssetter.utils.PrefKeys as K
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.SplsGate
import java.math.BigDecimal

typealias Getter = (String) -> Double?

class ValueField(
    val key: String,
    val hint: String,
    val integer: Boolean,
    val min: Double,
    val max: Double,
    val rangeError: String,
    val signed: Boolean = false
)

class ValueRow(val header: String?, val fields: List<ValueField>)

/** An on/off switch inside a dialog. [gate] returns a warning text when it may not be switched on. */
class ToggleSpec(
    val key: String,
    val label: String,
    val gate: (() -> String?)? = null,
    val disablesFields: Boolean = false,
    /** key of another switch in the same dialog: when this one is on, that one is switched off and greyed out */
    val exclusiveWith: String? = null,
    /** a linked (SPLS) switch: greyed out while no record is activated in Learned Behaviour Deployment */
    val linked: Boolean = false,
    /** small text under the switch */
    val note: String? = null,
    /** when switched off by the owner while [offWarnWhen] is true, ask first (buttons Keep on / Switch off) */
    val offWarning: String? = null,
    val offWarnWhen: (() -> Boolean)? = null,
    /** only active on this tab (0 or 1); -1 = always */
    val tab: Int = -1
)

/** Two tabs at the top of a dialog (a segmented button). The tab is stored under [key] as "true" for the second tab. */
class TabSpec(
    val key: String,
    val labels: List<String>,
    val descriptions: List<String>,
    /** the value fields are active only on this tab */
    val fieldTab: Int = 1
)

class ValueDialogSpec(
    val title: String,
    val description: String,
    val rows: List<ValueRow>,
    val cross: (List<Double?>) -> Map<Int, String>,
    val summary: (Getter) -> String,
    val toggles: List<ToggleSpec> = emptyList(),
    val tabs: TabSpec? = null,
    /** runs after Set or Clear, once the values are stored (for dependent settings) */
    val afterSave: (() -> Unit)? = null,
    /** conditionally disables all text fields in this dialog */
    val fieldsDisabledWhen: (() -> Boolean)? = null,
    /** explanatory note shown while the fields are disabled */
    val fieldsDisabledNote: String? = null
) {
    val fields: List<ValueField> get() = rows.flatMap { it.fields }
}

/** Dialogs, limits (brief section 7) and summaries for the ten value settings. */
object ValueSettings {

    private const val NOT_SET = "Not set"
    const val DEFAULT_INTERVAL_SECONDS = 5L

    private val DECIMAL = Regex("^-?(\\d+(\\.\\d+)?|\\.\\d+)$")
    private val WHOLE = Regex("^\\d+$")

    fun fmt(d: Double): String {
        val b = BigDecimal.valueOf(d)
        return if (b.signum() == 0) "0" else b.stripTrailingZeros().toPlainString()
    }

    private fun normalize(text: String): String {
        val b = BigDecimal(text)
        return if (b.signum() == 0) "0" else b.stripTrailingZeros().toPlainString()
    }

    fun durationText(total: Long): String {
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = ArrayList<String>()
        if (d > 0) parts.add("$d d")
        if (h > 0) parts.add("$h h")
        if (m > 0) parts.add("$m min")
        if (s > 0 || parts.isEmpty()) parts.add("$s s")
        return parts.joinToString(" ")
    }

    private fun none(): Map<Int, String> = emptyMap()

    private fun accField(key: String, hint: String, title: String) =
        ValueField(key, hint, false, 0.0, 100.0, "$title must be between 0 and 100 meters")

    private fun pairRule(v: List<Double?>): Map<Int, String> =
        if (v[0] == null && v[1] != null) mapOf(0 to "Enter this value first") else none()

    private fun pairSummary(unit: String, base: String, rand: String): (Getter) -> String = { g ->
        val b = g(base)
        if (b == null) NOT_SET else {
            val r = g(rand)
            fmt(b) + " $unit" + (if (r != null && r > 0) " ± ${fmt(r)} $unit" else "")
        }
    }

    /** Adds ", <text>" to a summary when the switch [flagKey] is on. */
    private fun withFlag(inner: (Getter) -> String, flagKey: String, text: String): (Getter) -> String = { g ->
        val s0 = inner(g)
        if (g(flagKey) == 1.0) (if (s0 == NOT_SET) text else "$s0, $text") else s0
    }

    private fun accuracySpec(title: String, description: String, base: String, rand: String, driftKey: String, learnedKey: String) = ValueDialogSpec(
        title,
        "$description When SPLS learned behaviour deployment is on, the accuracy follows the recorded blocks and the values above are ignored.",
        listOf(
            ValueRow(null, listOf(accField(base, "Accuracy (meters)", "Accuracy"))),
            ValueRow(null, listOf(accField(rand, "Randomize +/- (meters)", "Randomize")))
        ),
        { pairRule(it) },
        { g ->
            val typed = withFlag(pairSummary("m", base, rand), driftKey, "gradual drift")(g)
            if (g(learnedKey) == 1.0) (if (typed == NOT_SET) "SPLS learned" else "SPLS learned, typed values Ignored") else typed
        },
        listOf(
            ToggleSpec(driftKey, "Systematic accuracy drift"),
            ToggleSpec(learnedKey, "SPLS learned behaviour deployment", { SplsGate.check() },
                disablesFields = true, exclusiveWith = driftKey, linked = true)
        )
    )

    private fun intervalFields(h: String, m: String, s: String) = listOf(
        ValueField(h, "Hours", true, 0.0, 999.0, "Hours must be a whole number from 0 to 999"),
        ValueField(m, "Minutes", true, 0.0, 60.0, "Minutes cannot be more than 60"),
        ValueField(s, "Seconds", true, 0.0, 60.0, "Seconds cannot be more than 60")
    )

    fun intervalTotal(g: Getter, h: String, m: String, s: String): Long? {
        if (g(h) == null && g(m) == null && g(s) == null) return null
        return ((g(h) ?: 0.0) * 3600 + (g(m) ?: 0.0) * 60 + (g(s) ?: 0.0)).toLong()
    }

    private fun intervalRule(v: List<Double?>): Map<Int, String> {
        val custom = v.subList(0, 3)
        val rand = v.subList(3, 6)
        val cSet = custom.any { it != null }
        val rSet = rand.any { it != null }
        fun total(l: List<Double?>) = ((l[0] ?: 0.0) * 3600 + (l[1] ?: 0.0) * 60 + (l[2] ?: 0.0)).toLong()
        if (rSet && !cSet) return mapOf(0 to "Enter the custom interval first")
        if (cSet && total(custom) < 1) return mapOf(2 to "Interval must be at least 1 second")
        if (cSet && rSet && total(rand) > total(custom)) return mapOf(5 to "Randomize interval cannot be longer than the interval")
        return none()
    }

    private fun speedRule(v: List<Double?>): Map<Int, String> {
        val speed = v[0] ?: 0.0
        val r = v[1] ?: return none()
        if (r <= 0.0) return none()
        if (r > speed) return mapOf(1 to "Speed accuracy cannot be higher than the speed")
        if (r > 500.0 - speed) return mapOf(1 to "Speed accuracy cannot raise the speed above 500 m/s")
        return none()
    }

    fun specs(ctx: Context): Map<String, ValueDialogSpec> {
        val map = LinkedHashMap<String, ValueDialogSpec>()

        map["pref_network_accuracy"] = accuracySpec(
            ctx.getString(R.string.pref_network_accuracy),
            "How far off the position from Wi-Fi and mobile networks can be, in meters. The value changes around the number you set, within the randomize range.",
            K.NET_ACC, K.NET_ACC_RAND, K.NET_DRIFT, K.NET_LEARNED
        )
        map["pref_gps_accuracy"] = accuracySpec(
            ctx.getString(R.string.pref_gps_accuracy),
            "How far off the GPS (satellite) position can be, in meters. The value changes around the number you set, within the randomize range.",
            K.GPS_ACC, K.GPS_ACC_RAND, K.GPS_DRIFT, K.GPS_LEARNED
        )
        map["pref_fused_accuracy"] = accuracySpec(
            ctx.getString(R.string.pref_fused_accuracy),
            "How far off the fused position (Google's mix of GPS, Wi-Fi and network) can be, in meters. The value changes around the number you set, within the randomize range.",
            K.FUSED_ACC, K.FUSED_ACC_RAND, K.FUSED_DRIFT, K.FUSED_LEARNED
        )

        map["pref_horizontal_accuracy"] = ValueDialogSpec(
            ctx.getString(R.string.pref_horizontal_accuracy),
            "One accuracy value for every provider, in meters. Leave it empty to use the Network, GPS and Fused values. With Network Accuracy sync on, the network accuracy value is used and the fields are ignored.",
            listOf(
                ValueRow(null, listOf(ValueField(K.H_ACC, "Horizontal Accuracy (meters)", false, 0.0, 100.0, "Horizontal accuracy must be between 0 and 100 meters"))),
                ValueRow(null, listOf(ValueField(K.H_ACC_RAND, "Randomize +/- (meters)", false, 0.0, 100.0, "Randomize must be between 0 and 100 meters")))
            ),
            { pairRule(it) },
            { g ->
                val base = if (g(K.H_NET_SYNC) == 1.0) "Synced with network accuracy" else pairSummary("m", K.H_ACC, K.H_ACC_RAND)(g)
                val anyLearned = g(K.NET_LEARNED) == 1.0 || g(K.GPS_LEARNED) == 1.0 || g(K.FUSED_LEARNED) == 1.0
                if (anyLearned) "$base. Ignored for providers that use SPLS learned behaviour deployment" else base
            },
            listOf(ToggleSpec(K.H_NET_SYNC, "Network Accuracy sync", null, true))
        )

        map["pref_vertical_accuracy"] = ValueDialogSpec(
            ctx.getString(R.string.pref_vertical_accuracy),
            "How far off the height can be, in meters. It is never reported higher than the horizontal (provider) accuracy. Network-Linked Vertical Accuracy needs enough SPLS records.",
            listOf(
                ValueRow(null, listOf(ValueField(K.V_ACC, "Vertical Accuracy (meters)", false, 0.0, 100.0, "Vertical accuracy must be between 0 and 100 meters"))),
                ValueRow(null, listOf(ValueField(K.V_ACC_RAND, "Randomize +/- (meters)", false, 0.0, 100.0, "Randomize must be between 0 and 100 meters")))
            ),
            { pairRule(it) },
            withFlag(pairSummary("m", K.V_ACC, K.V_ACC_RAND), K.V_NET_LINK, "network-linked"),
            listOf(ToggleSpec(K.V_NET_LINK, "Network-Linked Vertical Accuracy", { SplsGate.check() }, linked = true))
        )

        map["pref_altitude"] = ValueDialogSpec(
            ctx.getString(R.string.pref_altitude),
            "Height above the WGS84 reference ellipsoid, in meters (GPS height). The randomize value makes it vary a little. Network-Linked Altitude needs enough SPLS records.",
            listOf(
                ValueRow(null, listOf(ValueField(K.ALT, "Altitude (meters)", false, -400.0, 5400.0, "Altitude must be between -400 and 5400 meters", true))),
                ValueRow(null, listOf(ValueField(K.ALT_RAND, "Randomize +/- (meters)", false, 0.0, 100.0, "Randomize must be between 0 and 100 meters")))
            ),
            { pairRule(it) },
            withFlag(pairSummary("m", K.ALT, K.ALT_RAND), K.ALT_NET_LINK, "network-linked"),
            listOf(ToggleSpec(K.ALT_NET_LINK, "Network-Linked Altitude", { SplsGate.check() }, linked = true))
        )

        map["pref_msl"] = ValueDialogSpec(
            ctx.getString(R.string.pref_msl),
            "Height above mean sea level, in meters, with its own accuracy. Android 14 and newer report it separately. Network-Linked MSL needs enough SPLS records.",
            listOf(
                ValueRow(null, listOf(ValueField(K.MSL, "MSL (meters)", false, -400.0, 5400.0, "MSL must be between -400 and 5400 meters", true))),
                ValueRow(null, listOf(ValueField(K.MSL_ACC, "MSL Accuracy (meters)", false, 0.0, 100.0, "MSL accuracy must be between 0 and 100 meters")))
            ),
            { none() },
            withFlag({ g ->
                val m = g(K.MSL)
                val a = g(K.MSL_ACC)
                if (m == null && a == null) NOT_SET
                else listOfNotNull(m?.let { "${fmt(it)} m" }, a?.let { "accuracy ${fmt(it)} m" }).joinToString(", ")
            }, K.MSL_NET_LINK, "network-linked"),
            listOf(ToggleSpec(K.MSL_NET_LINK, "Network-Linked MSL", { SplsGate.checkMsl() }, linked = true))
        )

        map["pref_speed"] = ValueDialogSpec(
            ctx.getString(R.string.pref_speed),
            "Speed in meters per second, with an optional randomize value. A network location always reports 0. Network-Linked speed needs enough SPLS records.",
            listOf(
                ValueRow(null, listOf(ValueField(K.SPEED, "Speed (m/s)", false, 0.0, 500.0, "Speed must be between 0 and 500 m/s"))),
                ValueRow(null, listOf(ValueField(K.SPEED_RAND, "Randomize +/- (m/s)", false, 0.0, 500.0, "Speed accuracy must be between 0 and 500 m/s")))
            ),
            { speedRule(it) },
            withFlag(pairSummary("m/s", K.SPEED, K.SPEED_RAND), K.SPEED_NET_LINK, "network-linked"),
            listOf(ToggleSpec(K.SPEED_NET_LINK, "Network-Linked speed", { SplsGate.check() }, linked = true))
        )

        val autoText = "The app works out the direction from how your fake location moves. Moving east reports about 90. " +
            "Moving north reports about 0. When your fake location stands still, no direction is reported, like a real phone."
        val fixedText = "You type one direction in degrees. 0 is north, 90 is east, 180 is south, 270 is west. " +
            "Every location update reports this direction, even when your fake location stands still. " +
            "A real phone usually sends no direction when it stands still."
        map["pref_bearing"] = ValueDialogSpec(
            ctx.getString(R.string.pref_bearing),
            autoText,
            listOf(ValueRow(null, listOf(ValueField(K.BEARING_FIXED, "Fixed bearing (degrees)", false, 0.0, 359.9, "Bearing must be between 0 and 359.9 degrees")))),
            { none() },
            { g ->
                if (g(K.BEARING_MODE) == 1.0) "Fixed ${fmt(g(K.BEARING_FIXED) ?: 0.0)} deg"
                else if (g(K.BEARING_LINK) == 1.0) "Auto from movement and SPLS-linked"
                else "Auto from movement"
            },
            listOf(
                ToggleSpec(K.BEARING_LINK, "Linked to SPLS", { SplsGate.checkBearing() }, linked = true, tab = 0,
                    note = "On: SPLS decides how often each provider sends a direction when your fake location stands still.")
            ),
            tabs = TabSpec(K.BEARING_MODE, listOf("Auto from movement", "Fixed"), listOf(autoText, fixedText), 1)
        )

        map["pref_bearing_accuracy"] = ValueDialogSpec(
            ctx.getString(R.string.pref_bearing_accuracy),
            "How sure the phone is about its direction, in degrees. A smaller number is better. It is only sent when a bearing is sent.",
            listOf(
                ValueRow(null, listOf(ValueField(K.BEARING_ACC, "Value (degrees)", false, 0.0, 180.0, "Bearing accuracy must be between 0 and 180 degrees"))),
                ValueRow(null, listOf(ValueField(K.BEARING_ACC_RAND, "Range +/- (degrees)", false, 0.0, 180.0, "Range must be between 0 and 180 degrees")))
            ),
            { v ->
                val base = pairRule(v)
                if (base.isNotEmpty()) base
                else if ((v[1] ?: 0.0) > (v[0] ?: 0.0)) mapOf(1 to "The range cannot be higher than the value") else none()
            },
            withFlag(pairSummary("deg", K.BEARING_ACC, K.BEARING_ACC_RAND), K.BEARING_ACC_LINK, "SPLS-linked"),
            listOf(
                ToggleSpec(K.BEARING_ACC_LINK, "Linked to SPLS", { SplsGate.checkBearing() }, disablesFields = true, linked = true,
                    note = "On: the learned accuracy of each provider is used and the fields above are ignored.")
            )
        )

        map["pref_position_jitter"] = ValueDialogSpec(
            ctx.getString(R.string.pref_position_jitter),
            "When on, each provider moves your fake position in small steps like the real provider did. " +
                "This replaces Randomize location while it is on. Switch on Position drift to keep the position near the centre.",
            emptyList(),
            { none() },
            { g -> if (g(K.JITTER_LINK) == 1.0) "SPLS-linked (replaces Randomize location)" else "Off" },
            listOf(
                ToggleSpec(K.JITTER_LINK, "Linked to SPLS", { SplsGate.checkJitter() }, linked = true,
                    note = "On: each provider moves the fake position by learned jitter steps and drifts back toward the centre. This replaces Randomize location while it is on.")
            ),
            afterSave = {
                // jitter on switches drift on (if its data allows); jitter off switches drift off
                if (PrefManager.getRaw(K.JITTER_LINK) == "true") {
                    if (SplsGate.checkDrift() == null) PrefManager.setRaw(K.DRIFT_LINK, "true")
                } else {
                    PrefManager.setRaw(K.DRIFT_LINK, null)
                }
            }
        )

        map["pref_position_drift"] = ValueDialogSpec(
            ctx.getString(R.string.pref_position_drift),
            "When on, your fake position is pulled back toward its recent average, like the real provider did. It needs Position jitter to be switched on.",
            emptyList(),
            { none() },
            { g ->
                if (g(K.DRIFT_LINK) == 1.0) "SPLS-linked"
                else if (g(K.JITTER_LINK) != 1.0) "Off. Needs Position jitter" else "Off"
            },
            listOf(
                ToggleSpec(K.DRIFT_LINK, "Linked to SPLS", { SplsGate.checkDrift() }, linked = true,
                    note = "On: the position is pulled back toward its recent average, using the learned drift of each provider.",
                    offWarning = "Without Position drift your fake position can wander away from the centre. Real providers do not do that.",
                    offWarnWhen = { PrefManager.getRaw(K.JITTER_LINK) == "true" })
            )
        )

        map["pref_random_radius"] = ValueDialogSpec(
            ctx.getString(R.string.pref_random_radius),
            "Moves your position randomly inside a circle of this radius around the chosen point. 0 or empty turns it off. While Position jitter is on, this setting is ignored.",
            listOf(ValueRow(null, listOf(ValueField(K.RADIUS, "Randomize radius (meters)", false, 0.0, 2000.0, "Radius must be between 0 and 2000 meters")))),
            { none() },
            { g ->
                val t = g(K.RADIUS)?.let { fmt(it) + " m" } ?: NOT_SET
                if (g(K.JITTER_LINK) == 1.0) "$t. Ignored while Position jitter is on" else t
            },
            fieldsDisabledWhen = { PrefManager.getRaw(K.JITTER_LINK) == "true" },
            fieldsDisabledNote = "Unavailable while Position jitter is enabled."
        )

        map["pref_random_interval"] = ValueDialogSpec(
            ctx.getString(R.string.pref_random_interval),
            "How often the random position changes. The second group adds a natural +/- variation to that time. If the first group is empty, it changes every ${DEFAULT_INTERVAL_SECONDS} seconds. While Position jitter is on, this setting is ignored.",
            listOf(
                ValueRow("Custom interval for location randomization", intervalFields(K.INT_H, K.INT_M, K.INT_S)),
                ValueRow("Randomize interval for location randomization (+/-)", intervalFields(K.INT_RH, K.INT_RM, K.INT_RS))
            ),
            { intervalRule(it) },
            { g ->
                val c = intervalTotal(g, K.INT_H, K.INT_M, K.INT_S)
                val t = if (c == null) "$NOT_SET (default every ${DEFAULT_INTERVAL_SECONDS} s)" else {
                    val r = intervalTotal(g, K.INT_RH, K.INT_RM, K.INT_RS)
                    "Every " + durationText(c) + (if (r != null && r > 0) " ± " + durationText(r) else "")
                }
                if (g(K.JITTER_LINK) == 1.0) "$t. Ignored while Position jitter is on" else t
            },
            fieldsDisabledWhen = { PrefManager.getRaw(K.JITTER_LINK) == "true" },
            fieldsDisabledNote = "Randomize location intervals are not used while Position jitter is enabled."
        )
        return map
    }

    /** "true" / "false" are read as 1.0 / 0.0 so switches can be used in summaries. */
    private fun readValue(key: String): Double? {
        val raw = PrefManager.getRaw(key)?.trim() ?: return null
        if (raw == "true") return 1.0
        if (raw == "false") return 0.0
        return raw.replace(',', '.').toDoubleOrNull()
    }

    fun summary(spec: ValueDialogSpec): String = spec.summary { readValue(it) }

    private fun parse(f: ValueField, raw: String): Pair<Double?, String?> {
        val t = raw.trim().replace(',', '.')
        if (t.isEmpty()) return Pair(null, null)
        if (f.integer) {
            if (!WHOLE.matches(t)) return Pair(null, "Use whole numbers only")
        } else if (!DECIMAL.matches(t)) {
            return Pair(null, "Enter a valid number")
        }
        val v = t.toDoubleOrNull() ?: return Pair(null, "Enter a valid number")
        if (v < f.min || v > f.max) return Pair(null, f.rangeError)
        return Pair(v, null)
    }

    fun show(ctx: Context, spec: ValueDialogSpec, onSaved: () -> Unit) {
        val density = ctx.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val gap = (8 * density).toInt()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val inflater = LayoutInflater.from(ctx)
        val fields = spec.fields
        val layouts = ArrayList<TextInputLayout>()
        val edits = ArrayList<TextInputEditText>()
        val tabs = spec.tabs
        var tab = if (tabs != null && PrefManager.getRaw(tabs.key) == "true") 1 else 0
        val hasRecord = SplsGate.hasDeployed()

        // tabs (segmented button) with a short description that changes with the tab
        val descView = TextView(ctx).apply {
            text = if (tabs != null) tabs.descriptions[tab] else spec.description
            textSize = 13f
            alpha = 0.8f
            setPadding(0, 0, 0, gap)
        }
        var onTabChanged: () -> Unit = {}
        if (tabs != null) {
            // Use ordinary TextViews instead of MaterialButton: MaterialButton was
            // forcing the long label into an ellipsis on narrow screens.
            val tabRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val tabButtons = ArrayList<TextView>()
            val selectedFill = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, Color.LTGRAY)
            val normalFill = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, Color.WHITE)
            val selectedText = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.BLACK)
            val normalText = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY)
            val outline = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY)
            fun updateTabAppearance() {
                for (index in tabButtons.indices) {
                    val selected = index == tab
                    val radii = if (index == 0) {
                        floatArrayOf(28f * density, 28f * density, 0f, 0f, 0f, 0f, 28f * density, 28f * density)
                    } else {
                        floatArrayOf(0f, 0f, 28f * density, 28f * density, 28f * density, 28f * density, 0f, 0f)
                    }
                    tabButtons[index].background = GradientDrawable().apply {
                        cornerRadii = radii
                        setColor(if (selected) selectedFill else normalFill)
                        setStroke((1 * density).toInt().coerceAtLeast(1), outline)
                    }
                    tabButtons[index].setTextColor(if (selected) selectedText else normalText)
                }
            }
            for ((index, label) in tabs.labels.withIndex()) {
                val button = TextView(ctx).apply {
                    text = if (label == "Auto from movement") "Auto\nfrom\nmovement" else label
                    textSize = 16f
                    setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                    gravity = Gravity.CENTER
                    maxLines = if (label == "Auto from movement") 3 else 2
                    isSingleLine = false
                    ellipsize = null
                    includeFontPadding = true
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                    minWidth = 0
                    isClickable = true
                    isFocusable = true
                    contentDescription = label
                    setOnClickListener {
                        tab = index
                        descView.text = tabs.descriptions[tab]
                        updateTabAppearance()
                        onTabChanged()
                    }
                }
                tabButtons.add(button)
                tabRow.addView(button, LinearLayout.LayoutParams(0, (76 * density).toInt(), 1f))
            }
            updateTabAppearance()
            column.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(View(ctx), LinearLayout.LayoutParams(1, gap))
        }
        column.addView(descView)

        for (row in spec.rows) {
            if (row.header != null) {
                column.addView(TextView(ctx).apply {
                    text = row.header
                    textSize = 15f
                    setPadding(0, gap, 0, gap / 2)
                })
            }
            val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, f) in row.fields.withIndex()) {
                val til = inflater.inflate(R.layout.dialog_value_field, rowView, false) as TextInputLayout
                (til.layoutParams as LinearLayout.LayoutParams).topMargin = gap / 2
                if (i > 0) (til.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                til.hint = f.hint
                val et = til.findViewById<TextInputEditText>(R.id.value_edit)
                var type = InputType.TYPE_CLASS_NUMBER
                if (!f.integer) type = type or InputType.TYPE_NUMBER_FLAG_DECIMAL
                if (f.signed) type = type or InputType.TYPE_NUMBER_FLAG_SIGNED
                et.inputType = type
                val allowed = if (f.integer) "0123456789" else if (f.signed) "0123456789.,-" else "0123456789.,"
                et.filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                    val sb = StringBuilder()
                    for (idx in start until end) if (allowed.indexOf(source[idx]) >= 0) sb.append(source[idx])
                    if (sb.length == end - start) null else sb.toString()
                })
                et.setText(PrefManager.getRaw(f.key) ?: "")
                rowView.addView(til)
                layouts.add(til)
                edits.add(et)
            }
            column.addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val fieldsDisabledNote = TextView(ctx).apply {
            text = spec.fieldsDisabledNote.orEmpty()
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, gap / 2, 0, gap / 2)
            visibility = View.GONE
        }
        if (spec.fieldsDisabledNote != null) column.addView(fieldsDisabledNote)

        // switches
        val switches = ArrayList<MaterialSwitch>()
        val lines = ArrayList<LinearLayout>()
        val warning = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#B3261E"))
            setPadding(0, gap / 2, 0, 0)
            visibility = View.GONE
        }
        var guard = false
        val keyIndex = spec.toggles.withIndex().associate { it.value.key to it.index }

        fun fieldsOff(): Boolean {
            if (tabs != null && tab != tabs.fieldTab) return true
            if (spec.fieldsDisabledWhen?.invoke() == true) return true
            return spec.toggles.indices.any { spec.toggles[it].disablesFields && switches[it].isChecked && switches[it].isEnabled }
        }
        fun refreshFields() {
            val off = fieldsOff()
            fieldsDisabledNote.visibility = if (spec.fieldsDisabledWhen?.invoke() == true) View.VISIBLE else View.GONE
            for (i in edits.indices) {
                edits[i].isEnabled = !off
                layouts[i].isEnabled = !off
                layouts[i].alpha = if (off) 0.45f else 1f
                if (off) layouts[i].error = null
            }
        }
        /** greys out a switch: no record (linked), the other switch of an exclusive pair is on, or the other tab */
        fun refreshToggles() {
            for ((i, t) in spec.toggles.withIndex()) {
                var enabled = true
                if (t.linked && !hasRecord) enabled = false
                if (t.tab >= 0 && t.tab != tab) enabled = false
                for ((j, o) in spec.toggles.withIndex()) {
                    if (o.exclusiveWith == t.key && switches[j].isChecked) enabled = false
                }
                switches[i].isEnabled = enabled
                lines[i].alpha = if (enabled) 1f else 0.45f
            }
        }
        for (t in spec.toggles) {
            val sw = MaterialSwitch(ctx)
            sw.isChecked = PrefManager.getRaw(t.key) == "true" && !(t.linked && !hasRecord)
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, gap, 0, 0)
            }
            line.addView(TextView(ctx).apply {
                text = t.label
                textSize = 15f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(sw)
            column.addView(line)
            if (t.note != null) {
                column.addView(TextView(ctx).apply {
                    text = t.note
                    textSize = 12f
                    alpha = 0.7f
                    setPadding(0, 0, 0, 0)
                })
            }
            switches.add(sw)
            lines.add(line)
        }
        for ((i, t) in spec.toggles.withIndex()) {
            val sw = switches[i]
            sw.setOnCheckedChangeListener { btn, checked ->
                if (guard) return@setOnCheckedChangeListener
                if (checked) {
                    val msg = t.gate?.invoke()
                    if (msg != null) {
                        guard = true
                        btn.isChecked = false
                        guard = false
                        warning.text = msg
                        warning.visibility = View.VISIBLE
                        return@setOnCheckedChangeListener
                    }
                    // an exclusive partner is switched off
                    t.exclusiveWith?.let { k -> keyIndex[k]?.let { j -> guard = true; switches[j].isChecked = false; guard = false } }
                } else if (t.offWarning != null && (t.offWarnWhen?.invoke() ?: true)) {
                    // ask before switching it off
                    guard = true
                    btn.isChecked = true
                    guard = false
                    MaterialAlertDialogBuilder(ctx)
                        .setMessage(t.offWarning)
                        .setPositiveButton("Keep on", null)
                        .setNegativeButton("Switch off") { _, _ ->
                            guard = true
                            btn.isChecked = false
                            guard = false
                            refreshToggles()
                            refreshFields()
                        }
                        .show()
                    return@setOnCheckedChangeListener
                }
                warning.visibility = View.GONE
                refreshToggles()
                refreshFields()
            }
        }
        if (spec.toggles.isNotEmpty()) column.addView(warning)
        onTabChanged = { refreshToggles(); refreshFields() }
        // a linked switch is greyed out while no record is activated; say why
        if (spec.toggles.any { it.linked } && !hasRecord) {
            warning.text = SplsGate.NO_RECORD_MESSAGE
            warning.visibility = View.VISIBLE
        }
        refreshToggles()
        refreshFields()

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(spec.title)
            .setView(ScrollView(ctx).apply { addView(column) })
            .setPositiveButton(R.string.dialog_set, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .apply {
                if (fields.isNotEmpty()) setNeutralButton(R.string.dialog_clear, null)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // a switch that is on must still be allowed (records can change)
                for ((i, t) in spec.toggles.withIndex()) {
                    if (switches[i].isChecked && switches[i].isEnabled) {
                        val msg = t.gate?.invoke()
                        if (msg != null) {
                            guard = true
                            switches[i].isChecked = false
                            guard = false
                            warning.text = msg
                            warning.visibility = View.VISIBLE
                            refreshToggles()
                            refreshFields()
                            return@setOnClickListener
                        }
                    }
                }
                val skipFields = fieldsOff()
                val values = ArrayList<Double?>()
                var ok = true
                if (!skipFields) {
                    for (i in fields.indices) {
                        layouts[i].error = null
                        val (v, err) = parse(fields[i], edits[i].text?.toString().orEmpty())
                        if (err != null) {
                            layouts[i].error = err
                            ok = false
                        }
                        values.add(v)
                    }
                    if (ok) {
                        val cross = spec.cross(values)
                        if (cross.isNotEmpty()) {
                            for ((idx, msg) in cross) layouts[idx].error = msg
                            ok = false
                        }
                    }
                }
                if (ok) {
                    if (!skipFields) {
                        if (values.all { it == null }) {
                            for (f in fields) PrefManager.setRaw(f.key, null)
                        } else {
                            for (i in fields.indices) {
                                val t = edits[i].text?.toString().orEmpty().trim().replace(',', '.')
                                PrefManager.setRaw(fields[i].key, if (values[i] == null) null else normalize(t))
                            }
                        }
                    }
                    if (tabs != null) PrefManager.setRaw(tabs.key, if (tab == 1) "true" else null)
                    for ((i, t) in spec.toggles.withIndex()) {
                        PrefManager.setRaw(t.key, if (switches[i].isChecked) "true" else null)
                    }
                    spec.afterSave?.invoke()
                    onSaved()
                    dialog.dismiss()
                }
            }
            if (fields.isNotEmpty()) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                for (f in fields) PrefManager.setRaw(f.key, null)
                for (t in spec.toggles) PrefManager.setRaw(t.key, null)
                if (tabs != null) PrefManager.setRaw(tabs.key, null)
                spec.afterSave?.invoke()
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
