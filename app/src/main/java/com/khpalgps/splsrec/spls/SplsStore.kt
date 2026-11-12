package com.khpalgps.splsrec.spls

import android.content.Context
import android.net.Uri
import com.khpalgps.splsrec.utils.PrefKeys as K
import com.khpalgps.splsrec.utils.PrefManager
import com.khpalgps.splsrec.utils.SplsBlocks
import com.khpalgps.splsrec.utils.SplsRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The SPLS locations (named records) and their rows (one CSV file per location).
 * The counters of the whole app are the sum of all locations; each location can also be counted alone.
 */
object SplsStore {

    class Counts(val gps: Int, val network: Int, val fused: Int, val bins: Map<String, IntArray>)

    private const val REGISTRY = "spls_locations.json"
    private const val OLD_FILE = "spls_records.csv"
    private const val HEADER = "run_id,datetime,provider,latitude,longitude,horizontal_acc,network_acc,vertical_acc,altitude_wgs84,altitude_msl,speed,bearing,bearing_acc,jitter_m,drift_m"
    private const val MAX_PER_PROVIDER = 6000
    private const val MAX_PER_BLOCK = 500
    private const val REBUILD_EVERY = 25

    private val lock = Any()
    private var locs: ArrayList<SplsLocation>? = null
    private val rowsById = HashMap<String, ArrayList<SplsRow>>()
    private var sinceRebuild = 0

    private fun dateFmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private fun runFmt() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private fun plain(d: Double?): String = if (d == null) "" else BigDecimal.valueOf(d).toPlainString()
    private fun rowFile(ctx: Context, id: String) = File(ctx.filesDir, "spls_$id.csv")

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Integer.toString((Math.random() * 1679616).toInt(), 36)

    // ------------------------------------------------------------ rows (CSV)

    private fun toLine(r: SplsRow): String {
        val d = Date(r.timeMs)
        return listOf(
            runFmt().format(d), dateFmt().format(d), r.provider,
            String.format(Locale.US, "%.8f", r.lat), String.format(Locale.US, "%.8f", r.lon),
            plain(r.hAcc), plain(r.netAcc), plain(r.vAcc), plain(r.alt), plain(r.msl), plain(r.speed),
            plain(r.bearing), plain(r.bearingAcc), plain(r.jitter), plain(r.drift)
        ).joinToString(",")
    }

    private fun parseLine(line: String): SplsRow? {
        val p = line.trim().split(',')
        if (p.size < 11) return null
        val provider = p[2].trim().lowercase(Locale.US)
        if (provider != "gps" && provider != "network" && provider != "fused") return null
        val time = try { dateFmt().parse(p[1].trim())?.time } catch (e: Exception) { null } ?: return null
        val lat = p[3].trim().toDoubleOrNull() ?: return null
        val lon = p[4].trim().toDoubleOrNull() ?: return null
        val h = p[5].trim().toDoubleOrNull() ?: return null
        val n = p[6].trim().toDoubleOrNull() ?: return null
        if (h <= 0.0 || n <= 0.0) return null
        return SplsRow(
            time, provider, lat, lon, h, n,
            p[7].trim().toDoubleOrNull(), p[8].trim().toDoubleOrNull(), p[9].trim().toDoubleOrNull(),
            p[10].trim().toDoubleOrNull() ?: 0.0,
            // older files have only 11 columns: bearing, jitter and drift stay empty
            p.getOrNull(11)?.trim()?.toDoubleOrNull(), p.getOrNull(12)?.trim()?.toDoubleOrNull(),
            p.getOrNull(13)?.trim()?.toDoubleOrNull(), p.getOrNull(14)?.trim()?.toDoubleOrNull()
        )
    }

    private fun key(r: SplsRow) = "${r.timeMs}|${r.provider}|${r.hAcc}"

    private fun rowsOf(ctx: Context, id: String): ArrayList<SplsRow> {
        rowsById[id]?.let { return it }
        val list = ArrayList<SplsRow>()
        val f = rowFile(ctx, id)
        if (f.exists()) f.forEachLine { line -> parseLine(line)?.let { list.add(it) } }
        rowsById[id] = list
        return list
    }

    private fun writeRows(ctx: Context, id: String, list: List<SplsRow>) {
        rowFile(ctx, id).bufferedWriter().use { w ->
            w.write(HEADER); w.newLine()
            for (r in list) { w.write(toLine(r)); w.newLine() }
        }
    }

    private fun countOf(list: List<SplsRow>, provider: String) = list.count { it.provider == provider }

    // ------------------------------------------------------------ locations (registry)

    private fun toJson(l: SplsLocation): JSONObject = JSONObject()
        .put("id", l.id).put("name", l.name).put("mode", l.mode).put("lat", l.lat).put("lon", l.lon)
        .put("radius", l.radius).put("completed", l.completed).put("createdAt", l.createdAt)

    private fun fromJson(o: JSONObject): SplsLocation = SplsLocation(
        o.getString("id"), o.getString("name"), o.optString("mode", SplsLocation.MODE_CONTINUOUS),
        o.optDouble("lat", 0.0), o.optDouble("lon", 0.0), o.optDouble("radius", SplsLocation.MIN_RADIUS),
        o.optBoolean("completed", false), o.optLong("createdAt", 0L)
    )

    private fun saveLocs(ctx: Context) {
        val arr = JSONArray()
        for (l in locs ?: emptyList<SplsLocation>()) arr.put(toJson(l))
        File(ctx.filesDir, REGISTRY).writeText(arr.toString())
    }

    private fun loadLocs(ctx: Context): ArrayList<SplsLocation> {
        locs?.let { return it }
        val list = ArrayList<SplsLocation>()
        val reg = File(ctx.filesDir, REGISTRY)
        if (reg.exists()) {
            try {
                val arr = JSONArray(reg.readText())
                for (i in 0 until arr.length()) list.add(fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
            }
        } else {
            // records of earlier versions (one file) become the first location, so nothing is lost
            val old = File(ctx.filesDir, OLD_FILE)
            if (old.exists() && old.length() > 0L) {
                val id = "earlier"
                val moved = File(ctx.filesDir, "spls_$id.csv")
                if (old.renameTo(moved)) {
                    val first = ArrayList<SplsRow>()
                    moved.forEachLine { line -> parseLine(line)?.let { first.add(it) } }
                    list.add(
                        SplsLocation(id, "Earlier records", SplsLocation.MODE_CONTINUOUS,
                            first.firstOrNull()?.lat ?: 0.0, first.firstOrNull()?.lon ?: 0.0,
                            SplsLocation.MIN_RADIUS, true, System.currentTimeMillis())
                    )
                }
            }
        }
        locs = list
        if (!reg.exists() && list.isNotEmpty()) saveLocs(ctx)
        return list
    }

    fun locations(ctx: Context): List<SplsLocation> = synchronized(lock) { ArrayList(loadLocs(ctx)) }

    fun find(ctx: Context, id: String): SplsLocation? = synchronized(lock) { loadLocs(ctx).firstOrNull { it.id == id } }

    fun rowCount(ctx: Context, id: String): Int = synchronized(lock) { rowsOf(ctx, id).size }

    fun hasCompleted(ctx: Context): Boolean = synchronized(lock) { loadLocs(ctx).any { it.completed } }

    fun addLocation(ctx: Context, l: SplsLocation) = synchronized(lock) {
        loadLocs(ctx).add(l)
        saveLocs(ctx)
    }

    fun update(ctx: Context, l: SplsLocation) = synchronized(lock) {
        val list = loadLocs(ctx)
        val i = list.indexOfFirst { it.id == l.id }
        if (i >= 0) list[i] = l
        saveLocs(ctx)
    }

    /** Deletes a location with its records. A running recording into it is ended. */
    fun deleteLocation(ctx: Context, id: String) = synchronized(lock) {
        loadLocs(ctx).removeAll { it.id == id }
        rowsById.remove(id)
        rowFile(ctx, id).delete()
        saveLocs(ctx)
        if (PrefManager.getRaw(K.SPLS_ACTIVE_ID) == id) {
            PrefManager.setRaw(K.SPLS_ENABLED, null)
            PrefManager.setRaw(K.SPLS_PAUSED, null)
            PrefManager.setRaw(K.SPLS_ACTIVE_ID, null)
            SplsRecorderService.stop(ctx.applicationContext)
        }
        rebuild(ctx)
    }

    // ------------------------------------------------------------ counters and model

    private fun effectiveRows(ctx: Context): List<SplsRow> {
        val ids = loadLocs(ctx).map { it.id }
        val out = ArrayList<SplsRow>()
        for (id in ids) out.addAll(rowsOf(ctx, id))
        return out
    }

    fun counts(ctx: Context): Counts = synchronized(lock) { countsOf(effectiveRows(ctx)) }

    fun countsOfLocation(ctx: Context, id: String): Counts = synchronized(lock) { countsOf(rowsOf(ctx, id)) }

    private fun countsOf(list: List<SplsRow>): Counts {
        val bins = mapOf("gps" to IntArray(SplsBlocks.COUNT), "network" to IntArray(SplsBlocks.COUNT), "fused" to IntArray(SplsBlocks.COUNT))
        for (r in list) {
            val b = bins[r.provider] ?: continue
            b[SplsBlocks.index(r.hAcc)]++
        }
        return Counts(countOf(list, "gps"), countOf(list, "network"), countOf(list, "fused"), bins)
    }

    /** Nothing to publish in this app (it has no learned model); kept so the recording code stays the same. */
    fun rebuild(ctx: Context) = synchronized(lock) {
        sinceRebuild = 0
    }

    // ------------------------------------------------------------ recording, import, export

    /** Adds one row to a location. Returns false when the limit for that provider or block is reached. */
    fun append(ctx: Context, id: String, r: SplsRow): Boolean = synchronized(lock) {
        val list = rowsOf(ctx, id)
        
        // Check total provider limit (6000)
        if (countOf(list, r.provider) >= MAX_PER_PROVIDER) return false
        
        // Check specific block limit (500)
        val blockIndex = SplsBlocks.index(r.hAcc)
        if (list.count { it.provider == r.provider && SplsBlocks.index(it.hAcc) == blockIndex } >= MAX_PER_BLOCK) return false

        list.add(r)
        val f = rowFile(ctx, id)
        val fresh = !f.exists() || f.length() == 0L
        java.io.FileWriter(f, true).use { w ->
            if (fresh) { w.write(HEADER); w.write("\n") }
            w.write(toLine(r)); w.write("\n")
        }
        sinceRebuild++
        if (sinceRebuild >= REBUILD_EVERY) rebuild(ctx)
        true
    }

    fun exportTo(ctx: Context, id: String, uri: Uri): Int = synchronized(lock) {
        val list = rowsOf(ctx, id)
        val out = ctx.contentResolver.openOutputStream(uri, "w") ?: throw java.io.IOException("Cannot open the file")
        out.bufferedWriter().use { w ->
            w.write(HEADER); w.newLine()
            for (r in list) { w.write(toLine(r)); w.newLine() }
        }
        list.size
    }

    /** Reads a CSV file into a new, finished location. Returns (added, skipped). */
    fun importAsLocation(ctx: Context, uri: Uri, name: String): Pair<Int, Int> = synchronized(lock) {
        val rows = ArrayList<SplsRow>()
        val known = HashSet<String>()
        var skipped = 0
        val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file")
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("run_id")) continue
                
                val r = parseLine(line)
                if (r == null || !known.add(key(r))) {
                    skipped++
                    continue
                }
                
                // Check total provider limit (6000)
                if (countOf(rows, r.provider) >= MAX_PER_PROVIDER) {
                    skipped++
                    continue
                }
                
                // Check specific block limit (500)
                val blockIndex = SplsBlocks.index(r.hAcc)
                if (rows.count { it.provider == r.provider && SplsBlocks.index(it.hAcc) == blockIndex } >= MAX_PER_BLOCK) {
                    skipped++
                    continue
                }
                
                rows.add(r)
            }
        }
        if (rows.isEmpty()) throw java.io.IOException("No usable records in this file")
        val id = newId()
        writeRows(ctx, id, rows)
        rowsById[id] = rows
        loadLocs(ctx).add(
            SplsLocation(id, name, SplsLocation.MODE_CONTINUOUS, rows.first().lat, rows.first().lon,
                SplsLocation.MIN_RADIUS, true, System.currentTimeMillis())
        )
        saveLocs(ctx)
        rebuild(ctx)
        Pair(rows.size, skipped)
    }
}
