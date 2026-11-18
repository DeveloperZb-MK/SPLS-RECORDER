package io.github.jqssun.gpssetter.spls

import android.content.Context
import android.net.Uri
import io.github.jqssun.gpssetter.utils.PrefKeys as K
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.SplsBlocks
import io.github.jqssun.gpssetter.utils.SplsGate
import io.github.jqssun.gpssetter.utils.SplsAccuracyModel
import io.github.jqssun.gpssetter.utils.SplsModelBuilder
import io.github.jqssun.gpssetter.utils.SplsMotionModel
import io.github.jqssun.gpssetter.utils.SplsBuildVersion
import io.github.jqssun.gpssetter.utils.SplsBuildVersionGuard
import io.github.jqssun.gpssetter.utils.SplsRebuildRequestGate
import io.github.jqssun.gpssetter.utils.SplsRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The SPLS locations (named records) and their rows (one CSV file per location).
 * Learned models are built only from the selected location while Learned Behaviour Deployment is on.
 * When deployment is off, no learned location model is used and the published models are cleared.
 */
object SplsStore {

    class Counts(val gps: Int, val network: Int, val fused: Int, val bins: Map<String, IntArray>)

    data class DiagnosticSnapshot(val deployedLocationId: String?, val revision: Long?, val rowCount: Int, val publishedLocationId: String?, val publishedRevision: Long?)

    private const val REGISTRY = "spls_locations.json"
    private const val OLD_FILE = "spls_records.csv"
    private const val HEADER = "run_id,datetime,provider,latitude,longitude,horizontal_acc,network_acc,vertical_acc,altitude_wgs84,altitude_msl,speed,bearing,bearing_acc,jitter_m,drift_m"
    private const val REBUILD_EVERY = 25

    private val lock = Any()
    private var locs: ArrayList<SplsLocation>? = null
    private val cacheById = HashMap<String, LocCache>()
    private var sinceRebuild = 0
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "khpal-spls-rebuild").apply { isDaemon = true } }
    private val rebuildQueue = SplsRebuildRequestGate()

    private val PROVIDER_LIST = listOf("gps", "network", "fused")

    /** The rows of one location with their counters (total per provider, count per accuracy block). Loaded once. */
    private class LocCache(val rows: ArrayList<SplsRow>) {
        // Incremented for every appended observation so a rebuild cannot publish an old snapshot.
        var revision: Long = 0L
        val total = HashMap<String, Int>()
        val bins = HashMap<String, IntArray>()
        init {
            for (p in listOf("gps", "network", "fused")) { total[p] = 0; bins[p] = IntArray(SplsBlocks.COUNT) }
        }
        fun count(r: SplsRow) {
            val b = bins[r.provider] ?: return
            b[SplsBlocks.index(r.hAcc)]++
            total[r.provider] = (total[r.provider] ?: 0) + 1
        }
        fun add(r: SplsRow) { rows.add(r); count(r); revision++ }
        fun refill() {
            for (p in bins.keys) { bins[p]!!.fill(0); total[p] = 0 }
            for (r in rows) count(r)
            // A freshly loaded cache carries a stable revision matching its persisted rows.
            revision = rows.size.toLong()
        }
    }

    private fun dateFmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private fun runFmt() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private fun plain(d: Double?): String = if (d == null) "" else BigDecimal.valueOf(d).toPlainString()
    private fun r2(d: Double?): String = if (d == null) "" else String.format(Locale.US, "%.2f", d)
    private fun rowFile(ctx: Context, id: String) = File(ctx.filesDir, "spls_$id.csv")

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Integer.toString((Math.random() * 1679616).toInt(), 36)

    // ------------------------------------------------------------ rows (CSV)

    private fun toLine(r: SplsRow): String {
        val d = Date(r.timeMs)
        return listOf(
            runFmt().format(d), dateFmt().format(d), r.provider,
            String.format(Locale.US, "%.8f", r.lat), String.format(Locale.US, "%.8f", r.lon),
            plain(r.hAcc), plain(r.netAcc), plain(r.vAcc), plain(r.alt), plain(r.msl), plain(r.speed),
            plain(r.bearing), plain(r.bearingAcc), r2(r.jitter), r2(r.drift)
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
            p[10].trim().toDoubleOrNull(),
            p.getOrNull(11)?.trim()?.toDoubleOrNull(),
            p.getOrNull(12)?.trim()?.toDoubleOrNull(),
            p.getOrNull(13)?.trim()?.toDoubleOrNull(),
            p.getOrNull(14)?.trim()?.toDoubleOrNull()
        )
    }

    private fun key(r: SplsRow) = "${r.timeMs}|${r.provider}|${r.hAcc}"

    private fun cacheOf(ctx: Context, id: String): LocCache {
        cacheById[id]?.let { return it }
        val list = ArrayList<SplsRow>()
        val f = rowFile(ctx, id)
        var oldFormat = false
        if (f.exists()) {
            f.bufferedReader().useLines { lines ->
                var first = true
                for (line in lines) {
                    if (first) { first = false; if (line.startsWith("run_id")) { oldFormat = !line.contains("jitter_m"); continue } }
                    parseLine(line)?.let { list.add(it) }
                }
            }
        }
        if (oldFormat) fillOldMotion(list)
        val c = LocCache(list)
        c.refill()
        cacheById[id] = c
        return c
    }

    private fun rowsOf(ctx: Context, id: String): ArrayList<SplsRow> = cacheOf(ctx, id).rows

    /**
     * Old rows (11 columns) have no jitter and no drift: work them out per provider in time order with the
     * recorder rules. Only rows 2.5 to 6 seconds after the previous row of the same provider are used.
     */
    private fun fillOldMotion(list: ArrayList<SplsRow>) {
        for (p in PROVIDER_LIST) {
            val idx = list.indices.filter { list[it].provider == p }.sortedBy { list[it].timeMs }
            val window = ArrayList<SplsRow>()
            var prev: SplsRow? = null
            for (i in idx) {
                val r = list[i]
                val gap = if (prev != null) r.timeMs - prev.timeMs else Long.MAX_VALUE
                if (prev == null || gap > 60_000L) window.clear()
                val inStep = prev != null && gap in 2500L..6000L
                var jitter: Double? = null
                var drift: Double? = null
                if (inStep) {
                    jitter = SplsGeo.distanceM(prev!!.lat, prev.lon, r.lat, r.lon)
                    if (window.size >= 3) {
                        val mLat = window.sumOf { it.lat } / window.size
                        val mLon = window.sumOf { it.lon } / window.size
                        drift = SplsGeo.distanceM(mLat, mLon, r.lat, r.lon)
                    }
                }
                if (jitter != null && r.jitter == null && r.drift == null) list[i] = r.copy(jitter = jitter, drift = drift)
                window.add(r)
                while (window.size > 20) window.removeAt(0)
                prev = r
            }
        }
    }

    private fun writeRows(ctx: Context, id: String, list: List<SplsRow>) {
        rowFile(ctx, id).bufferedWriter().use { w ->
            w.write(HEADER); w.newLine()
            for (r in list) { w.write(toLine(r)); w.newLine() }
        }
    }


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

    fun rowCount(ctx: Context, id: String): Int = synchronized(lock) { cacheOf(ctx, id).rows.size }

    /** Records of one provider (gps, network or fused) in one location. */
    fun providerCount(ctx: Context, id: String, provider: String): Int = synchronized(lock) { cacheOf(ctx, id).total[provider] ?: 0 }

    fun hasCompleted(ctx: Context): Boolean = synchronized(lock) { loadLocs(ctx).any { it.completed } }

    /** Read-only state snapshot for the diagnostic screen. */
    fun diagnosticSnapshot(ctx: Context): DiagnosticSnapshot = synchronized(lock) {
        val id = deployedId(ctx)
        val cache = id?.let { cacheOf(ctx, it) }
        DiagnosticSnapshot(
            id, cache?.revision, cache?.rows?.size ?: 0,
            PrefManager.getRaw("spls_diag_published_location"),
            PrefManager.getRaw("spls_diag_published_revision")?.toLongOrNull()
        )
    }

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
    fun deleteLocation(ctx: Context, id: String) {
        synchronized(lock) {
            loadLocs(ctx).removeAll { it.id == id }
            cacheById.remove(id)
            rowFile(ctx, id).delete()
            saveLocs(ctx)
            if (PrefManager.getRaw(K.SPLS_ACTIVE_ID) == id) {
                PrefManager.setRaw(K.SPLS_ENABLED, null)
                PrefManager.setRaw(K.SPLS_PAUSED, null)
                PrefManager.setRaw(K.SPLS_ACTIVE_ID, null)
                SplsRecorderService.stop(ctx.applicationContext)
            }
            if (PrefManager.getRaw(K.SPLS_SELECTED_ID) == id) PrefManager.setRaw(K.SPLS_SELECTED_ID, null)
        }
        // Rebuild only after the deletion lock has been released.
        rebuild(ctx)
    }

    // ------------------------------------------------------------ counters and model

    /** The record that is activated in Learned Behaviour Deployment, or null. Models and gates use only this record. */
    fun deployedId(ctx: Context): String? = synchronized(lock) {
        if (PrefManager.getRaw(K.SPLS_SELECT_ON) != "true") return@synchronized null
        val id = PrefManager.getRaw(K.SPLS_SELECTED_ID) ?: return@synchronized null
        if (loadLocs(ctx).any { it.id == id }) id else null
    }

    /** Counters of the activated record (what the gate sees). All zero when no record is activated. */
    fun counts(ctx: Context): Counts = synchronized(lock) {
        val id = deployedId(ctx) ?: return@synchronized emptyCounts()
        countsOfLocation(ctx, id)
    }

    /** View only: all records together. The gate never uses this. */
    fun countsAll(ctx: Context): Counts = synchronized(lock) {
        val gps = IntArray(1); val net = IntArray(1); val fus = IntArray(1)
        val bins = mapOf("gps" to IntArray(SplsBlocks.COUNT), "network" to IntArray(SplsBlocks.COUNT), "fused" to IntArray(SplsBlocks.COUNT))
        for (l in loadLocs(ctx)) {
            val c = cacheOf(ctx, l.id)
            gps[0] += c.total["gps"] ?: 0; net[0] += c.total["network"] ?: 0; fus[0] += c.total["fused"] ?: 0
            for ((p, arr) in bins) { val src = c.bins[p]!!; for (i in arr.indices) arr[i] += src[i] }
        }
        Counts(gps[0], net[0], fus[0], bins)
    }

    private fun emptyCounts() = Counts(0, 0, 0, mapOf(
        "gps" to IntArray(SplsBlocks.COUNT), "network" to IntArray(SplsBlocks.COUNT), "fused" to IntArray(SplsBlocks.COUNT)))

    /** Reads the counters only; the file is never parsed again. */
    fun countsOfLocation(ctx: Context, id: String): Counts = synchronized(lock) {
        val c = cacheOf(ctx, id)
        Counts(c.total["gps"] ?: 0, c.total["network"] ?: 0, c.total["fused"] ?: 0,
            c.bins.mapValues { it.value.copyOf() })
    }

    /**
     * Publishes counters and learned models for the deployed location.
     * The row snapshot is copied while holding the store lock, but all expensive model building happens
     * after the lock is released. A revision check prevents an older snapshot from replacing newer data.
     * When deployment is off (or no valid location is selected), all deployed model values are cleared.
     */
    fun rebuild(ctx: Context) {
        data class Snapshot(
            val locationId: String?,
            val revision: Long?,
            val counts: Counts,
            val rows: List<SplsRow>
        )

        val snapshot = synchronized(lock) {
            val id = deployedId(ctx)
            if (id == null) {
                Snapshot(null, null, emptyCounts(), emptyList())
            } else {
                val cache = cacheOf(ctx, id)
                val counts = Counts(
                    cache.total["gps"] ?: 0,
                    cache.total["network"] ?: 0,
                    cache.total["fused"] ?: 0,
                    cache.bins.mapValues { it.value.copyOf() }
                )
                Snapshot(id, cache.revision, counts, ArrayList(cache.rows))
            }
        }

        SplsDiagnostics.record(ctx, "REBUILD_STARTED", "location=${snapshot.locationId ?: "none"}; revision=${snapshot.revision ?: "n/a"}; rows=${snapshot.rows.size}")

        // Heavy calculations intentionally run outside synchronized(lock).
        val model = if (snapshot.rows.isEmpty()) null else SplsModelBuilder.build(snapshot.rows)
        val accModel = if (snapshot.rows.isEmpty()) null else SplsAccuracyModel.build(snapshot.rows)
        val motionModel = if (snapshot.rows.isEmpty()) null else SplsMotionModel.build(snapshot.rows)

        var retry = false
        synchronized(lock) {
            val currentId = deployedId(ctx)
            val currentRevision = currentId?.let { cacheOf(ctx, it).revision }
            val snapshotVersion = SplsBuildVersion(snapshot.locationId, snapshot.revision)
            val currentVersion = SplsBuildVersion(currentId, currentRevision)
            if (!SplsBuildVersionGuard.isCurrent(snapshotVersion, currentVersion)) {
                // Selection or rows changed while the model was being built. Do not publish stale results.
                retry = true
                SplsDiagnostics.record(ctx, "REBUILD_REJECTED_STALE", "snapshot=${snapshot.locationId ?: "none"}@${snapshot.revision ?: "n/a"}; current=${currentId ?: "none"}@$currentRevision")
            } else {
                val id = snapshot.locationId
                if (id == null) {
                    // Deployment OFF means no learned location model is in use. Keep recordings on disk,
                    // but clear deployed model/counter preferences so the hook cannot use an old location.
                    for (k in listOf(
                        K.SPLS_N_GPS, K.SPLS_N_NETWORK, K.SPLS_N_FUSED,
                        K.SPLS_BINS_GPS, K.SPLS_BINS_NETWORK, K.SPLS_BINS_FUSED,
                        K.SPLS_MODEL, K.SPLS_ACC_MODEL, K.SPLS_MOTION_MODEL
                    )) PrefManager.setRaw(k, null)
                    PrefManager.setRaw("spls_diag_published_location", null)
                    PrefManager.setRaw("spls_diag_published_revision", null)
                } else {
                    PrefManager.setRaw(K.SPLS_N_GPS, snapshot.counts.gps.toString())
                    PrefManager.setRaw(K.SPLS_N_NETWORK, snapshot.counts.network.toString())
                    PrefManager.setRaw(K.SPLS_N_FUSED, snapshot.counts.fused.toString())
                    PrefManager.setRaw(K.SPLS_BINS_GPS, snapshot.counts.bins["gps"]?.joinToString(","))
                    PrefManager.setRaw(K.SPLS_BINS_NETWORK, snapshot.counts.bins["network"]?.joinToString(","))
                    PrefManager.setRaw(K.SPLS_BINS_FUSED, snapshot.counts.bins["fused"]?.joinToString(","))
                    PrefManager.setRaw(K.SPLS_MODEL, model)
                    PrefManager.setRaw(K.SPLS_ACC_MODEL, accModel)
                    PrefManager.setRaw(K.SPLS_MOTION_MODEL, motionModel)
                    PrefManager.setRaw("spls_diag_published_location", id)
                    PrefManager.setRaw("spls_diag_published_revision", snapshot.revision?.toString())
                    SplsDiagnostics.record(ctx, "MODEL_PUBLISHED", "location=$id; revision=${snapshot.revision}; rows=${snapshot.rows.size}")
                }

                // linked settings that lost their data switch themselves off
                if (!SplsGate.allComplete() || id == null) {
                    for (k in K.LINKED_ALL) PrefManager.setRaw(k, null)
                } else {
                    if (SplsGate.checkJitter() != null) PrefManager.setRaw(K.JITTER_LINK, null)
                    if (SplsGate.checkDrift() != null) PrefManager.setRaw(K.DRIFT_LINK, null)
                    if (SplsGate.checkBearing() != null) {
                        PrefManager.setRaw(K.BEARING_LINK, null)
                        PrefManager.setRaw(K.BEARING_ACC_LINK, null)
                    }
                }
                // Do not discard append-trigger counts for rows that arrived during model building.
                if (id == null || currentRevision == snapshot.revision) sinceRebuild = 0
            }
        }
        if (retry) {
            SplsDiagnostics.record(ctx, "FOLLOW_UP_REBUILD_QUEUED", "stale snapshot will be rebuilt")
            rebuildAsync(ctx)
        }
    }

    /** Same as [rebuild], on a background thread. Multiple requests are coalesced. */
    fun rebuildAsync(ctx: Context) {
        // Atomic check-and-set prevents simultaneous callers from enqueueing duplicate rebuilds.
        if (!rebuildQueue.tryQueue()) {
            SplsDiagnostics.record(ctx, "REBUILD_REQUEST_COALESCED")
            return
        }
        SplsDiagnostics.record(ctx, "REBUILD_QUEUED")
        val app = ctx.applicationContext
        worker.execute {
            // Clear at task start: one follow-up rebuild may be queued while this snapshot is building.
            rebuildQueue.onTaskStarted()
            try { rebuild(app) } catch (e: Exception) {
                SplsDiagnostics.record(app, "REBUILD_ERROR", e.javaClass.simpleName + ": " + (e.message ?: "no message"))
            }
        }
    }

    // ------------------------------------------------------------ recording, import, export

    /** Adds one row to a location. Nothing is capped. The counters change by one; no list is scanned. */
    fun append(ctx: Context, id: String, r: SplsRow): Boolean = synchronized(lock) {
        val cache = cacheOf(ctx, id)
        val f = rowFile(ctx, id)
        val fresh = !f.exists() || f.length() == 0L
        // Persist first: a failed CSV write must not advance the in-memory rows or revision.
        try {
            SplsAppendTransaction(
                persist = { row ->
                    java.io.FileWriter(f, true).use { w ->
                        if (fresh) { w.write(HEADER); w.write("\n") }
                        w.write(toLine(row)); w.write("\n")
                    }
                },
                commit = { row -> cache.add(row) }
            ).append(r)
        } catch (e: Exception) {
            SplsDiagnostics.record(ctx, "OBSERVATION_SAVE_ERROR", e.javaClass.simpleName + ": " + (e.message ?: "no message"))
            throw e
        }
        SplsDiagnostics.record(ctx, "OBSERVATION_SAVED", "provider=${r.provider}; record=$id; revision=${cache.revision}; rows=${cache.rows.size}")
        sinceRebuild++
        if (sinceRebuild >= REBUILD_EVERY) { sinceRebuild = 0; rebuildAsync(ctx) }
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

    /** Reads a CSV file into a new, finished location. The file is read once. Returns (added, skipped). */
    fun importAsLocation(ctx: Context, uri: Uri, name: String): Pair<Int, Int> {
        val result = synchronized(lock) {
            val rows = ArrayList<SplsRow>()
            val known = HashSet<String>()
            var skipped = 0
            var oldFormat = false
            val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file")
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    if (line.startsWith("run_id")) { oldFormat = !line.contains("jitter_m"); continue }
                    val r = parseLine(line)
                    if (r == null || !known.add(key(r))) {
                        skipped++
                        continue
                    }
                    rows.add(r)
                }
            }
            if (rows.isEmpty()) throw java.io.IOException("No usable records in this file")
            if (oldFormat) fillOldMotion(rows)
            val id = newId()
            writeRows(ctx, id, rows)
            val cache = LocCache(rows)
            cache.refill()
            cacheById[id] = cache
            loadLocs(ctx).add(
                SplsLocation(id, name, SplsLocation.MODE_CONTINUOUS, rows.first().lat, rows.first().lon,
                    SplsLocation.MIN_RADIUS, true, System.currentTimeMillis())
            )
            saveLocs(ctx)
            Pair(rows.size, skipped)
        }
        // Rebuild only after the import lock has been released.
        rebuild(ctx)
        return result
    }
}
