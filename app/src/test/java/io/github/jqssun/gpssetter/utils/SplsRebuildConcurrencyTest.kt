package io.github.jqssun.gpssetter.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Regression tests for the version check that protects publication of asynchronously built models. */
class SplsRebuildConcurrencyTest {

    private fun row(index: Int): SplsRow = SplsRow(
        timeMs = 1_000_000L + index * 3_000L,
        provider = "gps",
        lat = 30.0,
        lon = 70.0,
        hAcc = 15.0,
        netAcc = 15.0,
        vAcc = null,
        alt = null,
        msl = null,
        speed = null
    )

    @Test
    fun unchangedLocationAndRevisionCanPublish() {
        val version = SplsBuildVersion("location-a", 42L)
        assertTrue(SplsBuildVersionGuard.isCurrent(version, version))
    }

    @Test
    fun observationsArrivingDuringBuildRejectTheOldSnapshot() {
        val lock = Any()
        val rows = ArrayList<SplsRow>((0 until 1_000).map(::row))
        var revision = 0L
        val snapshot = synchronized(lock) {
            SplsBuildVersion("location-a", revision) to ArrayList(rows)
        }

        val builderStarted = CountDownLatch(1)
        val allowBuilderToFinish = CountDownLatch(1)
        val builtJson = AtomicReference<String?>()
        val builder = Thread {
            builderStarted.countDown()
            check(allowBuilderToFinish.await(5, TimeUnit.SECONDS))
            builtJson.set(SplsAccuracyModel.build(snapshot.second))
        }
        builder.start()
        assertTrue("model builder did not start", builderStarted.await(5, TimeUnit.SECONDS))

        // Simulate 100 new observations while the old immutable snapshot is being processed.
        val currentVersion = synchronized(lock) {
            for (i in 1_000 until 1_100) rows.add(row(i))
            revision += 100L
            SplsBuildVersion("location-a", revision)
        }
        allowBuilderToFinish.countDown()
        builder.join(5_000)
        assertFalse("model builder did not finish", builder.isAlive)

        assertFalse(
            "a model built from 1,000 rows must not be published after 100 more rows arrive",
            SplsBuildVersionGuard.isCurrent(snapshot.first, currentVersion)
        )
        assertEquals(1_100, synchronized(lock) { rows.size })

        // The next snapshot includes all observations and is eligible for publication.
        val freshSnapshot = synchronized(lock) {
            SplsBuildVersion("location-a", revision) to ArrayList(rows)
        }
        assertTrue(SplsBuildVersionGuard.isCurrent(freshSnapshot.first, currentVersion))
        val freshJson = SplsAccuracyModel.build(freshSnapshot.second)
        assertTrue("fresh model should be built", !freshJson.isNullOrBlank())

        // Weighted model mass is 3 seconds per row, proving the rebuilt model saw all 1,100 rows.
        val binValues = JSONObject(freshJson!!).getJSONObject("gps")
            .getJSONArray("values").getJSONArray(SplsBlocks.index(15.0))
        assertEquals(3_300.0, binValues.getJSONArray(0).getDouble(1), 0.001)
        assertTrue("stale model should have been built from its original snapshot", !builtJson.get().isNullOrBlank())
        val staleBinValues = JSONObject(builtJson.get()!!).getJSONObject("gps")
            .getJSONArray("values").getJSONArray(SplsBlocks.index(15.0))
        assertEquals(3_000.0, staleBinValues.getJSONArray(0).getDouble(1), 0.001)
    }

    @Test
    fun switchingSelectedLocationRejectsPreviousLocationModel() {
        val snapshot = SplsBuildVersion("location-a", 10L)
        val current = SplsBuildVersion("location-b", 10L)
        assertFalse(SplsBuildVersionGuard.isCurrent(snapshot, current))
    }

    @Test
    fun turningDeploymentOffRejectsPreviouslySelectedLocationModel() {
        val snapshot = SplsBuildVersion("location-a", 10L)
        val current = SplsBuildVersion(null, null)
        assertFalse(SplsBuildVersionGuard.isCurrent(snapshot, current))
    }

    @Test
    fun simultaneousRequestsAreCoalescedButOneFollowUpCanBeQueued() {
        val gate = SplsRebuildRequestGate()
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger(0)
        val threads = (0 until 20).map {
            Thread {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                if (gate.tryQueue()) accepted.incrementAndGet()
            }.apply { start() }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals("only one queued rebuild should be accepted", 1, accepted.get())

        gate.onTaskStarted()
        assertTrue("a follow-up request during a running build must be accepted", gate.tryQueue())
        assertFalse("duplicate follow-up requests must be coalesced", gate.tryQueue())
    }

    @Test
    fun noSelectedLocationRemainsCurrentWhenDeploymentIsOff() {
        assertTrue(
            SplsBuildVersionGuard.isCurrent(
                SplsBuildVersion(null, null),
                SplsBuildVersion(null, null)
            )
        )
    }
}
