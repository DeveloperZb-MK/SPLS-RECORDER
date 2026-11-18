package io.github.jqssun.gpssetter.spls

import android.content.Context
import io.github.jqssun.gpssetter.utils.PrefKeys as K
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.SplsModel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, on-device SPLS diagnostics. Never stores coordinates or changes SPLS behaviour. */
object SplsDiagnostics {
    enum class Status { PASS, FAIL, WAITING, INFO }
    data class Check(val title: String, val status: Status, val detail: String)
    private const val FILE = "spls_diagnostics.jsonl"
    private const val MAX_EVENTS = 1000
    private const val MAX_BYTES = 512 * 1024
    private val lock = Any()

    fun record(ctx: Context, event: String, details: String = "") {
        try { synchronized(lock) {
            val f = File(ctx.applicationContext.filesDir, FILE)
            f.appendText(JSONObject().put("time", System.currentTimeMillis()).put("event", event).put("details", details.take(1200)).toString() + "\n")
            if (f.length() > MAX_BYTES) f.writeText(f.readLines().takeLast(MAX_EVENTS).joinToString("\n", postfix = "\n"))
        } } catch (_: Exception) { /* diagnostics must never interrupt recording */ }
    }

    /** Deletes only the diagnostic event history; SPLS records and models are untouched. */
    fun clearEvents(ctx: Context): Boolean = try { synchronized(lock) {
        val f = File(ctx.applicationContext.filesDir, FILE)
        !f.exists() || f.delete()
    } } catch (_: Exception) { false }

    fun events(ctx: Context): List<String> = try { synchronized(lock) {
        val f = File(ctx.applicationContext.filesDir, FILE)
        if (!f.exists()) emptyList() else {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            f.readLines().takeLast(MAX_EVENTS).asReversed().mapNotNull { line -> try {
                val o = JSONObject(line)
                fmt.format(Date(o.optLong("time"))) + "  " + o.optString("event") +
                    if (o.optString("details").isBlank()) "" else "\n    " + o.optString("details")
            } catch (_: Exception) { null } }
        }
    } } catch (_: Exception) { emptyList() }

    fun runChecks(ctx: Context): List<Check> {
        val out = ArrayList<Check>()
        try {
            val enabled = PrefManager.getRaw(K.SPLS_ENABLED) == "true"
            val paused = PrefManager.getRaw(K.SPLS_PAUSED) == "true"
            val activeId = PrefManager.getRaw(K.SPLS_ACTIVE_ID)
            val exists = activeId == null || SplsStore.find(ctx, activeId) != null
            val ok = (enabled == (activeId != null)) && (!paused || enabled) && exists
            out.add(Check("Recording session state", if (ok) Status.PASS else Status.FAIL, "recording=$enabled, paused=$paused, activeRecord=${activeId ?: "none"}, recordExists=$exists"))
        } catch (e: Exception) { out.add(Check("Recording session state", Status.FAIL, e.message ?: e.javaClass.simpleName)) }

        try {
            val locations = SplsStore.locations(ctx)
            val rows = locations.sumOf { SplsStore.rowCount(ctx, it.id).toLong() }
            val c = SplsStore.countsAll(ctx)
            val totals = c.gps.toLong() + c.network.toLong() + c.fused.toLong()
            out.add(Check("Stored rows vs provider counters", if (rows == totals) Status.PASS else Status.FAIL, "rows=$rows, providerCounters=$totals (GPS=${c.gps}, Network=${c.network}, Fused=${c.fused})"))
        } catch (e: Exception) { out.add(Check("Stored rows vs provider counters", Status.FAIL, e.message ?: e.javaClass.simpleName)) }

        if (PrefManager.getRaw(K.SPLS_SELECT_ON) != "true") {
            out.add(Check("Provider model separation", Status.INFO, "Deployment is OFF; no deployed provider model is expected."))
            out.add(Check("Published snapshot freshness", Status.INFO, "Deployment is OFF; no deployed snapshot is expected."))
        } else {
            try {
                val id = SplsStore.deployedId(ctx)
                if (id == null) out.add(Check("Provider model separation", Status.FAIL, "Deployment is ON but no valid selected record exists."))
                else {
                    val c = SplsStore.counts(ctx)
                    val expected = buildSet { if (c.gps > 0) add("gps"); if (c.network > 0) add("network"); if (c.fused > 0) add("fused") }
                    val raw = PrefManager.getRaw(K.SPLS_MODEL)
                    val parsed = if (raw.isNullOrBlank()) null else SplsModel.parse(raw)
                    val providers = try { JSONObject(raw ?: "").optJSONObject("p")?.keys()?.asSequence()?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() }
                    val valid = if (expected.isEmpty()) raw.isNullOrBlank() || parsed != null else parsed != null && providers.containsAll(expected) && providers.all { it in setOf("gps", "network", "fused") }
                    out.add(Check("Provider model separation", if (valid) Status.PASS else Status.FAIL, "expected=${expected.sorted()}, modelProviders=${providers.sorted()}"))
                    val accRaw = PrefManager.getRaw(K.SPLS_ACC_MODEL)
                    val motionRaw = PrefManager.getRaw(K.SPLS_MOTION_MODEL)
                    val accParsed = if (accRaw.isNullOrBlank()) null else io.github.jqssun.gpssetter.utils.SplsAccuracyModel.parse(accRaw)
                    val motionParsed = if (motionRaw.isNullOrBlank()) null else io.github.jqssun.gpssetter.utils.SplsMotionModel.parse(motionRaw)
                    val structureOk = if (expected.isEmpty()) (accRaw.isNullOrBlank() || accParsed != null) && (motionRaw.isNullOrBlank() || motionParsed != null) else accParsed != null && motionParsed != null
                    out.add(Check("Accuracy and motion model structure", if (structureOk) Status.PASS else Status.FAIL, "accuracyModel=${if (accParsed == null) "missing/invalid" else "parsed"}, motionModel=${if (motionParsed == null) "missing/invalid" else "parsed"}"))
                }
            } catch (e: Exception) { out.add(Check("Provider model separation", Status.FAIL, e.message ?: e.javaClass.simpleName)) }
            try {
                val s = SplsStore.diagnosticSnapshot(ctx)
                val current = s.deployedLocationId != null && s.deployedLocationId == s.publishedLocationId && s.revision != null && s.revision == s.publishedRevision
                val waiting = s.deployedLocationId != null && (s.publishedLocationId == null || s.revision != s.publishedRevision)
                val status = if (current) Status.PASS else if (waiting) Status.WAITING else Status.FAIL
                out.add(Check("Published snapshot freshness", status, "current=${s.deployedLocationId ?: "none"}@${s.revision ?: "n/a"}, published=${s.publishedLocationId ?: "none"}@${s.publishedRevision ?: "n/a"}, rows=${s.rowCount}"))
            } catch (e: Exception) { out.add(Check("Published snapshot freshness", Status.FAIL, e.message ?: e.javaClass.simpleName)) }
        }
        return out
    }

    fun export(ctx: Context): String = buildString {
        appendLine("KHPAL GPS — SPLS Diagnostic Report")
        appendLine("Generated: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        appendLine(); appendLine("SELF-CHECKS")
        runChecks(ctx).forEach { appendLine("[${it.status}] ${it.title}"); appendLine("  ${it.detail}") }
        appendLine(); appendLine("EVENT HISTORY (newest first)")
        val history = events(ctx)
        if (history.isEmpty()) appendLine("No events recorded yet.") else history.forEach { appendLine(it) }
    }
}
