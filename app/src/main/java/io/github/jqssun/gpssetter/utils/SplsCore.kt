package io.github.jqssun.gpssetter.utils

import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import kotlin.math.abs

/**
 * The 12 accuracy ranges ("bins") that SPLS counts samples in. A record is counted in the range of
 * the accuracy of its own provider (a network record by network accuracy, a GPS record by GPS accuracy, ...).
 */
object SplsBlocks {
    const val COUNT = 12
    const val MIN_TOTAL = 500

    val LABELS = listOf(
        "0 to <5 m", "5 to <10 m", "10 to <20 m", "20 to <30 m", "30 to <40 m",
        "40 to <50 m", "50 to <60 m", "60 to <80 m", "80 to <100 m",
        "Exactly 100 m", ">100 to <130 m", "130 m and above"
    )

    // minimum samples per range; 0 = the range is optional
    private val NETWORK_MIN = intArrayOf(0, 0, 15, 15, 15, 15, 15, 15, 0, 0, 0, 0)
    private val GPS_MIN = intArrayOf(0, 0, 15, 15, 15, 0, 0, 0, 0, 0, 0, 0)
    private val FUSED_MIN = intArrayOf(10, 10, 15, 15, 15, 0, 0, 0, 0, 0, 0, 0)

    /** Half-open ranges: 0 <= accuracy < 5.0 m is the first range; exactly 5.0 m belongs to "5 to <10 m". */
    fun index(acc: Double): Int = when {
        acc >= 0.0 && acc < 5.0 -> 0
        acc < 10.0 -> 1
        acc < 20.0 -> 2
        acc < 30.0 -> 3
        acc < 40.0 -> 4
        acc < 50.0 -> 5
        acc < 60.0 -> 6
        acc < 80.0 -> 7
        acc < 100.0 -> 8
        acc == 100.0 -> 9
        acc < 130.0 -> 10
        else -> 11
    }

    /** Minimum samples for a range of a provider ("network", "gps" or "fused"); 0 = optional. */
    fun minSamples(provider: String, bin: Int): Int = when (provider) {
        "network" -> NETWORK_MIN[bin]
        "gps" -> GPS_MIN[bin]
        else -> FUSED_MIN[bin]
    }

    /** A provider is complete with at least [MIN_TOTAL] records and every compulsory range filled. */
    fun isComplete(provider: String, total: Int, bins: IntArray): Boolean {
        if (total < MIN_TOTAL) return false
        for (i in 0 until COUNT) if (bins[i] < minSamples(provider, i)) return false
        return true
    }
}

/** Identifies the exact location-data snapshot from which a set of learned models was built. */
data class SplsBuildVersion(val locationId: String?, val revision: Long?)

/** Shared publication rule used by the store and its deterministic concurrency regression tests. */
object SplsBuildVersionGuard {
    /** A model is publishable only if its location and, for a selected location, row revision still match. */
    fun isCurrent(snapshot: SplsBuildVersion, current: SplsBuildVersion): Boolean =
        snapshot.locationId == current.locationId &&
            (snapshot.locationId == null || snapshot.revision == current.revision)
}

/** Coalesces queued rebuild requests without a racy check-then-set sequence. */
class SplsRebuildRequestGate {
    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryQueue(): Boolean = queued.compareAndSet(false, true)

    /** Called when the queued task starts; a single follow-up task may then be queued while it runs. */
    fun onTaskStarted() { queued.set(false) }
}

/** One recorded location (one line of the CSV file). */
data class SplsRow(
    val timeMs: Long,
    val provider: String,
    val lat: Double,
    val lon: Double,
    val hAcc: Double,
    /** accuracy of the network position at that moment (for a network row: its own accuracy) */
    val netAcc: Double,
    val vAcc: Double?,
    val alt: Double?,
    val msl: Double?,
    /** null = Android gave no speed (never 0) */
    val speed: Double?,
    /** degrees clockwise from true north; null = none */
    val bearing: Double? = null,
    val bearingAcc: Double? = null,
    /** meters from the previous row of the same provider (null = not known) */
    val jitter: Double? = null,
    /** meters from the average of the previous rows of the same provider */
    val drift: Double? = null
)

/** Values learned for one provider at one network accuracy; null = no data. */
data class SplsSample(
    val vert: Double?,
    val speed: Double?,
    val altDev: Double?,
    val mslDev: Double?
)

/** Turns recorded rows into a compact learned-behaviour model for SPLS deployment. */
object SplsModelBuilder {

    private const val MAX_PER_BLOCK = 20
    private val PROVIDERS = listOf("gps", "network", "fused")

    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun num(d: Double?): Any = if (d == null || d.isNaN() || d.isInfinite()) JSONObject.NULL else Math.round(d * 100.0) / 100.0

    fun build(rows: List<SplsRow>): String {
        val root = JSONObject()
        root.put("v", 1)
        val prov = JSONObject()
        for (p in PROVIDERS) {
            val pr = rows.filter { it.provider == p && it.hAcc > 0.0 }
            if (pr.isEmpty()) continue
            val altMed = median(pr.mapNotNull { it.alt })
            val mslMed = median(pr.mapNotNull { it.msl })
            val po = JSONObject()
            val byBlock = pr.groupBy { SplsBlocks.index(it.hAcc) }
            for ((b, list) in byBlock) {
                val pick = if (list.size > MAX_PER_BLOCK) list.shuffled(Random(7L)).take(MAX_PER_BLOCK) else list
                val arr = JSONArray()
                for (r in pick) {
                    val a = JSONArray()
                    a.put(num(r.vAcc))
                    a.put(num(r.speed))
                    a.put(num(if (r.alt != null && altMed != null) r.alt - altMed else null))
                    a.put(num(if (r.msl != null && mslMed != null) r.msl - mslMed else null))
                    arr.put(a)
                }
                po.put(b.toString(), arr)
            }
            prov.put(p, po)
        }
        root.put("p", prov)
        return root.toString()
    }
}

/** The learned behaviour: for a provider and a network accuracy it gives realistic values. */
class SplsModel private constructor(private val data: Map<String, Map<Int, List<DoubleArray>>>) {

    /** Picks one recorded row from the same (or the nearest) network accuracy range. */
    fun sample(provider: String, acc: Double, rnd: Random): SplsSample? {
        val blocks = data[provider] ?: return null
        val idx = SplsBlocks.index(acc)
        var best: List<DoubleArray>? = null
        for (b in (0 until SplsBlocks.COUNT).sortedBy { abs(it - idx) }) {
            val l = blocks[b]
            if (l != null && l.size >= 3) {
                best = l
                break
            }
        }
        if (best == null) {
            for (l in blocks.values) {
                if (l.isNotEmpty()) {
                    best = l
                    break
                }
            }
        }
        val rows = best ?: return null
        val r = rows[rnd.nextInt(rows.size)]
        fun v(x: Double): Double? = if (x.isNaN()) null else x
        return SplsSample(v(r[0]), v(r[1]), v(r[2]), v(r[3]))
    }

    companion object {
        fun parse(json: String): SplsModel? {
            return try {
                val p = JSONObject(json).getJSONObject("p")
                val out = HashMap<String, Map<Int, List<DoubleArray>>>()
                val names = p.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val po = p.getJSONObject(name)
                    val blocks = HashMap<Int, List<DoubleArray>>()
                    val ks = po.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        val arr = po.getJSONArray(k)
                        val list = ArrayList<DoubleArray>()
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONArray(i)
                            val row = DoubleArray(4) { j -> if (a.isNull(j)) Double.NaN else a.getDouble(j) }
                            list.add(row)
                        }
                        blocks[k.toInt()] = list
                    }
                    out[name] = blocks
                }
                SplsModel(out)
            } catch (e: Exception) {
                null
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Learned accuracy model (blocks with their share, values, and the steps between blocks)
// ---------------------------------------------------------------------------------------------------------

/** A list of values with weights that can be sampled. */
class SplsHist(val values: DoubleArray, val weights: DoubleArray) {
    private val total = weights.sum()
    val isEmpty: Boolean get() = values.isEmpty() || total <= 0.0

    fun sample(rnd: Random): Double {
        var x = rnd.nextDouble() * total
        for (i in values.indices) {
            x -= weights[i]
            if (x <= 0.0) return values[i]
        }
        return values[values.size - 1]
    }

    /** Value below which [q] (0..1) of the weight lies. */
    fun quantile(q: Double): Double {
        var x = q * total
        for (i in values.indices) {
            x -= weights[i]
            if (x <= 0.0) return values[i]
        }
        return values[values.size - 1]
    }

    companion object {
        fun fromJson(a: JSONArray?): SplsHist? {
            if (a == null || a.length() == 0) return null
            val v = DoubleArray(a.length())
            val w = DoubleArray(a.length())
            for (i in 0 until a.length()) {
                val e = a.getJSONArray(i)
                v[i] = e.getDouble(0)
                w[i] = e.getDouble(1)
            }
            return SplsHist(v, w)
        }

        fun pickIndex(row: DoubleArray, rnd: Random): Int {
            var sum = 0.0
            for (x in row) sum += x
            if (sum <= 0.0) return -1
            var x = rnd.nextDouble() * sum
            for (i in row.indices) {
                x -= row[i]
                if (x <= 0.0) return i
            }
            return row.size - 1
        }
    }
}

/** Learned accuracy of one provider. */
class SplsAccProvider(
    val share: DoubleArray,
    val values: Array<SplsHist?>,
    val next: Array<DoubleArray>
)

object SplsAccuracyModel {

    private val PROVIDERS = listOf("network", "gps", "fused")

    private fun r4(d: Double) = Math.round(d * 10000.0) / 10000.0

    /** Builds the accuracy model JSON from the rows of the activated record. */
    fun build(rows: List<SplsRow>): String? {
        val root = JSONObject()
        root.put("v", 1)
        var any = false
        for (p in PROVIDERS) {
            val pr = rows.filter { it.provider == p && it.hAcc > 0.0 }.sortedBy { it.timeMs }
            if (pr.isEmpty()) continue
            any = true
            val n = pr.size
            val share = DoubleArray(SplsBlocks.COUNT)
            val values = Array(SplsBlocks.COUNT) { java.util.TreeMap<Double, Double>() }
            val pairs = Array(SplsBlocks.COUNT) { DoubleArray(SplsBlocks.COUNT) }
            val pairCount = IntArray(SplsBlocks.COUNT)
            for (i in 0 until n) {
                val r = pr[i]
                val w = if (i + 1 < n) ((pr[i + 1].timeMs - r.timeMs) / 1000.0).coerceIn(1.0, 30.0) else 3.0
                val b = SplsBlocks.index(r.hAcc)
                share[b] += w
                val v = Math.round(r.hAcc * 10.0) / 10.0
                values[b][v] = (values[b][v] ?: 0.0) + w
                if (i + 1 < n && pr[i + 1].timeMs - r.timeMs <= 60_000L) {
                    pairs[b][SplsBlocks.index(pr[i + 1].hAcc)] += 1.0
                    pairCount[b]++
                }
            }
            val sum = share.sum()
            for (b in share.indices) share[b] = if (sum > 0) share[b] / sum else 0.0
            val po = JSONObject()
            po.put("share", JSONArray().also { a -> for (x in share) a.put(r4(x)) })
            val vals = JSONArray()
            for (b in 0 until SplsBlocks.COUNT) {
                var m: Map<Double, Double> = values[b]
                if (m.size > 200) {
                    val merged = java.util.TreeMap<Double, Double>()
                    for ((k, w) in m) {
                        val kk = Math.round(k * 2.0) / 2.0
                        merged[kk] = (merged[kk] ?: 0.0) + w
                    }
                    m = merged
                }
                val arr = JSONArray()
                for ((k, w) in m) arr.put(JSONArray().put(k).put(r4(w)))
                vals.put(arr)
            }
            po.put("values", vals)
            val nextArr = JSONArray()
            for (b in 0 until SplsBlocks.COUNT) {
                val row = JSONArray()
                if (pairCount[b] >= 5) {
                    for (c in 0 until SplsBlocks.COUNT) row.put(r4(pairs[b][c] / pairCount[b]))
                } else {
                    for (c in 0 until SplsBlocks.COUNT) row.put(r4(share[c]))
                }
                nextArr.put(row)
            }
            po.put("next", nextArr)
            root.put(p, po)
        }
        return if (any) root.toString() else null
    }

    fun parse(json: String?): Map<String, SplsAccProvider>? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            val out = HashMap<String, SplsAccProvider>()
            for (p in PROVIDERS) {
                val po = root.optJSONObject(p) ?: continue
                val sh = po.getJSONArray("share")
                val share = DoubleArray(SplsBlocks.COUNT) { sh.getDouble(it) }
                val va = po.getJSONArray("values")
                val values = Array(SplsBlocks.COUNT) { SplsHist.fromJson(va.optJSONArray(it)) }
                val na = po.getJSONArray("next")
                val next = Array(SplsBlocks.COUNT) { r ->
                    val row = na.getJSONArray(r)
                    DoubleArray(SplsBlocks.COUNT) { row.getDouble(it) }
                }
                out[p] = SplsAccProvider(share, values, next)
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Learned motion model (jitter, drift, bearing habits; learned only from rows where the phone stood still)
// ---------------------------------------------------------------------------------------------------------

class SplsMotionProvider(
    val still: Int,
    val jitter: SplsHist?,
    val drift: SplsHist?,
    val driftP95: Double?,
    /** null = the record has no bearing data */
    val bearingShare: Double?,
    val bearingAcc: SplsHist?
)

object SplsMotionModel {

    private val PROVIDERS = listOf("network", "gps", "fused")
    const val STILL_SPEED = 0.5
    const val MIN_STILL = 100

    private fun r4(d: Double) = Math.round(d * 10000.0) / 10000.0

    private fun histJson(values: List<Double>, step: Double): JSONArray {
        val m = java.util.TreeMap<Double, Int>()
        for (v in values) {
            val k = Math.round(v / step) * step
            val kk = Math.round(k * 100.0) / 100.0
            m[kk] = (m[kk] ?: 0) + 1
        }
        val a = JSONArray()
        for ((k, c) in m) a.put(JSONArray().put(k).put(c))
        return a
    }

    private fun trim(values: List<Double>, stillCount: Int): List<Double> {
        if (stillCount < 100 || values.size < 2) return values
        val s = values.sorted()
        val keep = Math.max(1, (s.size * 0.99).toInt())
        return s.subList(0, keep)
    }

    private fun percentile(sorted: List<Double>, q: Double): Double? {
        if (sorted.isEmpty()) return null
        val i = Math.min(sorted.size - 1, Math.max(0, Math.ceil(q * sorted.size).toInt() - 1))
        return sorted[i]
    }

    fun build(rows: List<SplsRow>): String? {
        // speeds of GPS and Fused rows, sorted by time: they decide stillness of rows that have no speed
        val speedRows = rows.filter { (it.provider == "gps" || it.provider == "fused") && it.speed != null }.sortedBy { it.timeMs }
        val speedTimes = LongArray(speedRows.size) { speedRows[it].timeMs }

        fun nearbyStill(t: Long): Boolean? {
            if (speedRows.isEmpty()) return null
            var lo = 0
            var hi = speedTimes.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (speedTimes[mid] < t - 5000L) lo = mid + 1 else hi = mid
            }
            var found = false
            var i = lo
            while (i < speedTimes.size && speedTimes[i] <= t + 5000L) {
                found = true
                if (speedRows[i].speed!! >= STILL_SPEED) return false
                i++
            }
            return if (found) true else null
        }

        val hasBearingData = rows.any { it.bearing != null }
        val root = JSONObject()
        root.put("v", 1)
        var any = false
        for (p in PROVIDERS) {
            val pr = rows.filter { it.provider == p }.sortedBy { it.timeMs }
            if (pr.isEmpty()) continue
            any = true
            val jit = ArrayList<Double>()
            val dri = ArrayList<Double>()
            var stillAll = 0
            var stillWithBearing = 0
            val bAcc = ArrayList<Double>()
            for (i in pr.indices) {
                val r = pr[i]
                val still: Boolean = when {
                    r.speed != null -> r.speed < STILL_SPEED
                    else -> nearbyStill(r.timeMs) ?: false
                }
                if (r.bearing != null && r.bearingAcc != null) bAcc.add(r.bearingAcc)
                if (!still) continue
                stillAll++
                if (r.bearing != null) stillWithBearing++
                val gap = if (i > 0) r.timeMs - pr[i - 1].timeMs else -1L
                if (gap in 2500L..6000L) {
                    if (r.jitter != null) jit.add(r.jitter)
                    if (r.drift != null) dri.add(r.drift)
                }
            }
            val usable = jit.size
            val jitT = trim(jit, usable)
            val driT = trim(dri, usable)
            val po = JSONObject()
            po.put("still", usable)
            po.put("jitter", histJson(jitT, 0.1))
            po.put("drift", histJson(driT, 0.1))
            percentile(driT.sorted(), 0.95)?.let { po.put("driftP95", r4(it)) }
            if (hasBearingData) {
                po.put("bearingShare", r4(if (stillAll > 0) stillWithBearing.toDouble() / stillAll else 0.0))
                po.put("bearingAcc", histJson(bAcc, 1.0))
            }
            root.put(p, po)
        }
        return if (any) root.toString() else null
    }

    fun parse(json: String?): Map<String, SplsMotionProvider>? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            val out = HashMap<String, SplsMotionProvider>()
            for (p in PROVIDERS) {
                val po = root.optJSONObject(p) ?: continue
                out[p] = SplsMotionProvider(
                    po.optInt("still", 0),
                    SplsHist.fromJson(po.optJSONArray("jitter")),
                    SplsHist.fromJson(po.optJSONArray("drift")),
                    if (po.has("driftP95")) po.getDouble("driftP95") else null,
                    if (po.has("bearingShare")) po.getDouble("bearingShare") else null,
                    SplsHist.fromJson(po.optJSONArray("bearingAcc"))
                )
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            null
        }
    }
}
