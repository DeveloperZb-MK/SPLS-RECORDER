package io.github.jqssun.gpssetter.spls

import io.github.jqssun.gpssetter.utils.SplsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SplsAppendTransactionTest {
    private fun row() = SplsRow(
        timeMs = 1_000L,
        provider = "gps",
        lat = 30.0,
        lon = 70.0,
        hAcc = 8.0,
        netAcc = 8.0,
        vAcc = null,
        alt = null,
        msl = null,
        speed = null
    )

    @Test
    fun failedPersistenceDoesNotCommitRowToMemory() {
        val cached = ArrayList<SplsRow>()
        val transaction = SplsAppendTransaction(
            persist = { throw IOException("simulated disk write failure") },
            commit = { cached.add(it) }
        )

        assertThrows(IOException::class.java) { transaction.append(row()) }
        assertEquals("failed disk writes must not change cached rows", 0, cached.size)
    }

    @Test
    fun successfulPersistenceCommitsExactlyOnceAndOnlyAfterPersistence() {
        val events = ArrayList<String>()
        val cached = ArrayList<SplsRow>()
        val transaction = SplsAppendTransaction(
            persist = { events.add("persist") },
            commit = { events.add("commit"); cached.add(it) }
        )

        transaction.append(row())

        assertEquals(listOf("persist", "commit"), events)
        assertEquals(1, cached.size)
    }
}
