package io.github.jqssun.gpssetter.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Random

/**
 * Additional deterministic SPLS reliability checks.
 *
 * These exercise the pure model builders and the version/queue primitives. They deliberately do not
 * pretend to exercise Android Context, private SplsStore state, disk I/O, or the real worker thread.
 */
class SplsReliabilityTest {

    private fun row(
        provider: String,
        index: Int,
        accuracy: Double = 15.0,
        timeMs: Long = 1_000_000L + index * 3_000L
    ) = SplsRow(
        timeMs = timeMs,
        provider = provider,
        lat = 30.0 + index * 0.000001,
        lon = 70.0 + index * 0.000001,
        hAcc = accuracy,
        netAcc = accuracy,
        vAcc = 2.0,
        alt = 1500.0 + index,
        msl = 1450.0 + index,
        speed = 0.0,
        bearing = 90.0,
        bearingAcc = 5.0,
        jitter = 0.2,
        drift = 0.3
    )

    @Test
    fun emptyAndSingleObservationRecordingsAreHandledSafely() {
        val empty = emptyList<SplsRow>()
        val emptyModel = JSONObject(SplsModelBuilder.build(empty))
        assertEquals(0, emptyModel.getJSONObject("p").length())
        assertNull(SplsAccuracyModel.build(empty))
        assertNull(SplsMotionModel.build(empty))

        val one = listOf(row("gps", 0))
        assertNotNull(SplsModelBuilder.build(one))
        assertNotNull(SplsAccuracyModel.build(one))
        assertNotNull(SplsMotionModel.build(one))
    }

    @Test
    fun gpsNetworkAndFusedModelsRemainProviderSeparated() {
        val rows = buildList {
            repeat(30) { add(row("gps", it, 7.5)) }
            repeat(30) { add(row("network", it, 45.0)) }
            repeat(30) { add(row("fused", it, 85.0)) }
        }

        val behaviourProviders = JSONObject(SplsModelBuilder.build(rows))
            .getJSONObject("p")
        assertEquals(setOf("gps", "network", "fused"), behaviourProviders.keys().asSequence().toSet())

        val accuracy = SplsAccuracyModel.parse(SplsAccuracyModel.build(rows))!!
        assertEquals(setOf("gps", "network", "fused"), accuracy.keys)
        assertEquals(1.0, accuracy["gps"]!!.share[SplsBlocks.index(7.5)], 0.0001)
        assertEquals(1.0, accuracy["network"]!!.share[SplsBlocks.index(45.0)], 0.0001)
        assertEquals(1.0, accuracy["fused"]!!.share[SplsBlocks.index(85.0)], 0.0001)

        val motion = SplsMotionModel.parse(SplsMotionModel.build(rows))!!
        assertEquals(setOf("gps", "network", "fused"), motion.keys)
    }

    @Test
    fun accuracyTransitionsAreBuiltInChronologicalOrder() {
        val chronological = (0 until 40).map { i ->
            row("gps", i, if (i % 2 == 0) 7.5 else 15.0)
        }
        val shuffled = chronological.reversed()
        val model = SplsAccuracyModel.parse(SplsAccuracyModel.build(shuffled))!!["gps"]!!

        val low = SplsBlocks.index(7.5)
        val high = SplsBlocks.index(15.0)
        assertEquals(1.0, model.next[low][high], 0.0001)
        assertEquals(1.0, model.next[high][low], 0.0001)
    }

    @Test
    fun duplicateRowsAreCountedAsRepeatedRecordedSamples() {
        val duplicate = row("gps", 0, 15.0, 1_000_000L)
        val model = SplsAccuracyModel.parse(
            SplsAccuracyModel.build(listOf(duplicate, duplicate.copy(), duplicate.copy()))
        )!!["gps"]!!

        val histogram = model.values[SplsBlocks.index(15.0)]!!
        assertEquals(5.0, histogram.weights.sum(), 0.0001)
    }

    @Test
    fun allThreeBuildersReadTheSameUnmodifiedSnapshot() {
        val mutableRows = ArrayList<SplsRow>()
        repeat(100) { i ->
            val provider = listOf("gps", "network", "fused")[i % 3]
            mutableRows.add(row(provider, i))
        }
        val snapshot = Collections.unmodifiableList(ArrayList(mutableRows))
        val before = snapshot.toList()

        val behaviour = JSONObject(SplsModelBuilder.build(snapshot)).getJSONObject("p")
        val accuracy = SplsAccuracyModel.parse(SplsAccuracyModel.build(snapshot))!!
        val motion = SplsMotionModel.parse(SplsMotionModel.build(snapshot))!!

        assertEquals(before, snapshot)
        assertEquals(setOf("gps", "network", "fused"), behaviour.keys().asSequence().toSet())
        assertEquals(setOf("gps", "network", "fused"), accuracy.keys)
        assertEquals(setOf("gps", "network", "fused"), motion.keys)
    }

    @Test
    fun largeRecordingCanBeModelledWithoutLosingProviderSamples() {
        val rows = ArrayList<SplsRow>(50_000)
        repeat(50_000) { i ->
            val provider = when (i % 3) {
                0 -> "gps"
                1 -> "network"
                else -> "fused"
            }
            rows.add(row(provider, i, 5.0 + (i % 8) * 10.0))
        }

        val json = SplsAccuracyModel.build(rows)
        assertNotNull(json)
        val parsed = SplsAccuracyModel.parse(json)!!
        assertEquals(setOf("gps", "network", "fused"), parsed.keys)
        for (provider in listOf("gps", "network", "fused")) {
            assertTrue(parsed[provider]!!.share.sum() > 0.999)
        }
    }

    @Test
    fun observationDuringBuildMakesSnapshotStaleAndLeavesFollowUpQueued() {
        val gate = SplsRebuildRequestGate()
        assertTrue(gate.tryQueue())
        gate.onTaskStarted()

        val old = SplsBuildVersion("location-a", 1_000L)
        val latest = SplsBuildVersion("location-a", 1_100L)
        assertTrue("append-triggered follow-up must be queueable during a build", gate.tryQueue())
        assertFalse("a stale snapshot must not be publishable", SplsBuildVersionGuard.isCurrent(old, latest))
        assertFalse("retry must not enqueue a duplicate when the follow-up is already queued", gate.tryQueue())
    }

    @Test
    fun repeatedConcurrentFollowUpRequestsAreCoalesced() {
        val gate = SplsRebuildRequestGate()
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val ready = java.util.concurrent.CountDownLatch(32)
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 32).map {
            Thread {
                ready.countDown()
                check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (gate.tryQueue()) accepted.incrementAndGet()
            }.apply { start() }
        }
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, accepted.get())
    }

    @Test
    fun deploymentOffAndLocationSwitchInvalidatePublishedSnapshot() {
        val built = SplsBuildVersion("location-a", 25L)
        assertFalse(SplsBuildVersionGuard.isCurrent(built, SplsBuildVersion(null, null)))
        assertFalse(SplsBuildVersionGuard.isCurrent(built, SplsBuildVersion("location-b", 25L)))
        assertTrue(SplsBuildVersionGuard.isCurrent(
            SplsBuildVersion(null, null),
            SplsBuildVersion(null, null)
        ))
    }

    @Test
    fun accuracySamplerMatchesTheRecordedValuesAndTheirApproximateShares() {
        val values = listOf(6.5, 7.5, 8.5)
        val rows = (0 until 900).map { i ->
            row("gps", i, values[i % values.size])
        }
        val model = SplsAccuracyModel.parse(SplsAccuracyModel.build(rows))!!["gps"]!!
        val histogram = model.values[SplsBlocks.index(7.5)]!!
        assertEquals(values.toSet(), histogram.values.toSet())

        val generated = HashMap<Double, Int>()
        val random = Random(991L)
        repeat(9_000) {
            val sampled = histogram.sample(random)
            assertTrue("sampler produced a value absent from the recording", sampled in values)
            generated[sampled] = (generated[sampled] ?: 0) + 1
        }
        for (value in values) {
            val share = (generated[value] ?: 0).toDouble() / 9_000.0
            assertTrue("sample share for $value was $share", kotlin.math.abs(share - 1.0 / 3.0) < 0.025)
        }
    }

    @Test
    fun behaviourSamplesNeverBorrowValuesFromAnotherProvider() {
        val rows = buildList {
            repeat(40) { i ->
                add(row("gps", i, 10.0).copy(vAcc = 1.0, speed = 0.1, alt = 100.0, msl = 90.0))
                add(row("network", i, 40.0).copy(vAcc = 8.0, speed = 4.0, alt = 500.0, msl = 450.0))
                add(row("fused", i, 80.0).copy(vAcc = 20.0, speed = 12.0, alt = 1_000.0, msl = 950.0))
            }
        }
        val model = SplsModel.parse(SplsModelBuilder.build(rows))!!
        val random = Random(17L)

        assertEquals(1.0, model.sample("gps", 10.0, random)!!.vert!!, 0.001)
        assertEquals(8.0, model.sample("network", 40.0, random)!!.vert!!, 0.001)
        assertEquals(20.0, model.sample("fused", 80.0, random)!!.vert!!, 0.001)
    }

    @Test
    fun invalidAccuracyRowsAreExcludedAndMissingOrNonFiniteOptionalMetricsStayMissing() {
        val invalidAccuracyRows = listOf(
            row("gps", 0, 0.0),
            row("gps", 1, -5.0),
            row("gps", 2, Double.NaN),
            row("gps", 3, 15.0)
        )
        val accuracy = SplsAccuracyModel.parse(SplsAccuracyModel.build(invalidAccuracyRows))!!["gps"]!!
        assertEquals(1.0, accuracy.share[SplsBlocks.index(15.0)], 0.0001)
        assertEquals(0.0, accuracy.share.sum() - accuracy.share[SplsBlocks.index(15.0)], 0.0001)

        val unusualOptionalValues = row("gps", 4, 15.0).copy(
            vAcc = Double.NaN,
            speed = Double.POSITIVE_INFINITY,
            alt = Double.NEGATIVE_INFINITY,
            msl = null
        )
        val behaviour = SplsModel.parse(SplsModelBuilder.build(listOf(unusualOptionalValues)))!!
        val sampled = behaviour.sample("gps", 15.0, Random(1L))!!
        assertNull(sampled.vert)
        assertNull(sampled.speed)
        assertNull(sampled.altDev)
        assertNull(sampled.mslDev)
    }

    @Test
    fun largeSnapshotCanBeConsumedByAllThreeBuildersWithoutMutation() {
        val rows = ArrayList<SplsRow>(10_000)
        repeat(10_000) { i ->
            val provider = when (i % 3) {
                0 -> "gps"
                1 -> "network"
                else -> "fused"
            }
            rows.add(row(provider, i, 5.0 + (i % 8) * 10.0))
        }
        val snapshot = Collections.unmodifiableList(ArrayList(rows))
        val sizeBefore = snapshot.size
        val behaviour = SplsModel.parse(SplsModelBuilder.build(snapshot))
        val accuracy = SplsAccuracyModel.parse(SplsAccuracyModel.build(snapshot))
        val motion = SplsMotionModel.parse(SplsMotionModel.build(snapshot))

        assertNotNull(behaviour)
        assertNotNull(accuracy)
        assertNotNull(motion)
        assertEquals(sizeBefore, snapshot.size)
        assertEquals(10_000, snapshot.count { it.provider == "gps" || it.provider == "network" || it.provider == "fused" })
    }

}
