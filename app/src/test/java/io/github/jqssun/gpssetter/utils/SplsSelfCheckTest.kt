package io.github.jqssun.gpssetter.utils

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * SPLS accuracy-model self-check. These tests use deterministic synthetic records, so GitHub Actions
 * does not need a phone, GPS receiver, or a user-recorded CSV file.
 *
 * Each provider is checked independently for:
 *  - reproduction of its recorded accuracy-block distribution;
 *  - reproduction of its recorded block-to-block transitions;
 *  - reproduction of the overall probability of staying in the same block.
 */
class SplsSelfCheckTest {

    private val accuracyByState = doubleArrayOf(3.5, 7.5, 15.0)

    private fun syntheticRows(provider: String, seed: Long): List<SplsRow> {
        val random = Random(seed)
        val rows = ArrayList<SplsRow>(6000)
        var state = 1
        var time = 1_000_000L

        repeat(6000) {
            // A three-state Markov walk with enough samples and transitions in every used state.
            state = when (state) {
                0 -> if (random.nextDouble() < 0.10) 1 else 0
                1 -> {
                    val move = random.nextDouble()
                    when {
                        move < 0.15 -> 0
                        move < 0.30 -> 2
                        else -> 1
                    }
                }
                else -> if (random.nextDouble() < 0.10) 1 else 2
            }

            val accuracy = accuracyByState[state]
            rows.add(
                SplsRow(
                    timeMs = time,
                    provider = provider,
                    lat = 30.0,
                    lon = 70.0,
                    hAcc = accuracy,
                    netAcc = accuracy,
                    vAcc = null,
                    alt = null,
                    msl = null,
                    speed = null
                )
            )
            time += 3000L
        }
        return rows
    }

    private fun assertProviderModelMatchesRecording(provider: String, seed: Long) {
        val recorded = syntheticRows(provider, seed)
        val json = SplsAccuracyModel.build(recorded)
        assertNotNull("$provider accuracy model JSON was not built", json)

        val parsed = SplsAccuracyModel.parse(json)
        assertNotNull("$provider accuracy model JSON could not be parsed", parsed)
        val model = parsed!![provider]
        assertNotNull("$provider model was missing after parsing", model)
        val providerModel = model!!

        val expectedBlocks = IntArray(SplsBlocks.COUNT)
        val expectedTransitions = Array(SplsBlocks.COUNT) { IntArray(SplsBlocks.COUNT) }
        val expectedFrom = IntArray(SplsBlocks.COUNT)
        for (row in recorded) expectedBlocks[SplsBlocks.index(row.hAcc)]++
        for (i in 0 until recorded.lastIndex) {
            val from = SplsBlocks.index(recorded[i].hAcc)
            val to = SplsBlocks.index(recorded[i + 1].hAcc)
            expectedTransitions[from][to]++
            expectedFrom[from]++
        }

        val generatedBlocks = IntArray(SplsBlocks.COUNT)
        val generatedTransitions = Array(SplsBlocks.COUNT) { IntArray(SplsBlocks.COUNT) }
        val generatedFrom = IntArray(SplsBlocks.COUNT)
        val generatedCount = 50_000
        val random = Random(seed + 10_000L)
        var current = -1
        var generatedStay = 0

        repeat(generatedCount) {
            val weights = if (current < 0) providerModel.share else providerModel.next[current]
            var next = SplsHist.pickIndex(weights, random)
            if (next < 0) next = SplsHist.pickIndex(providerModel.share, random)
            assertTrue("$provider model produced no usable accuracy block", next in 0 until SplsBlocks.COUNT)

            val histogram = providerModel.values[next]
            assertNotNull("$provider model has no accuracy values for block $next", histogram)
            val generatedAccuracy = histogram!!.sample(random)
            assertTrue(
                "$provider model sampled ${generatedAccuracy}m for the wrong block $next",
                SplsBlocks.index(generatedAccuracy) == next
            )

            generatedBlocks[next]++
            if (current >= 0) {
                generatedTransitions[current][next]++
                generatedFrom[current]++
                if (current == next) generatedStay++
            }
            current = next
        }

        // Check that the generated model reproduces the recorded block shares (3 percentage points).
        for (block in 0 until SplsBlocks.COUNT) {
            val expected = expectedBlocks[block].toDouble() / recorded.size
            val actual = generatedBlocks[block].toDouble() / generatedCount
            val differencePoints = abs(actual - expected) * 100.0
            assertTrue(
                "$provider block $block share differs by $differencePoints percentage points",
                differencePoints <= 3.0
            )
        }

        // Check the provider's overall tendency to remain in the same accuracy block (5 points).
        val expectedStay = expectedTransitions.indices.sumOf { expectedTransitions[it][it] }.toDouble() /
            (recorded.size - 1)
        val actualStay = generatedStay.toDouble() / (generatedCount - 1)
        val stayDifferencePoints = abs(actualStay - expectedStay) * 100.0
        assertTrue(
            "$provider same-block transition share differs by $stayDifferencePoints percentage points",
            stayDifferencePoints <= 5.0
        )

        // Check each used transition row, not just the combined same-block percentage.
        for (from in 0 until SplsBlocks.COUNT) {
            if (expectedFrom[from] < 30) continue
            assertTrue("$provider block $from had no generated outgoing transitions", generatedFrom[from] > 0)
            for (to in 0 until SplsBlocks.COUNT) {
                val expected = expectedTransitions[from][to].toDouble() / expectedFrom[from]
                val actual = generatedTransitions[from][to].toDouble() / generatedFrom[from]
                val differencePoints = abs(actual - expected) * 100.0
                assertTrue(
                    "$provider transition $from->$to differs by $differencePoints percentage points",
                    differencePoints <= 5.0
                )
            }
        }
    }

    @Test
    fun gpsAccuracyDistributionAndTransitionsMatchRecording() {
        assertProviderModelMatchesRecording("gps", 101L)
    }

    @Test
    fun networkAccuracyDistributionAndTransitionsMatchRecording() {
        assertProviderModelMatchesRecording("network", 202L)
    }

    @Test
    fun fusedAccuracyDistributionAndTransitionsMatchRecording() {
        assertProviderModelMatchesRecording("fused", 303L)
    }
}
