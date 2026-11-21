package com.splsrecorder.zb.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplsProviderAccuracyTest {
    @Test
    fun eachRowExposesOnlyItsOwnProviderAccuracy() {
        val gps = row("gps", 4.5)
        val network = row("network", 35.0)
        val fused = row("fused", 75.0)

        assertEquals(4.5, gps.providerHorizontalAccuracy()!!, 0.0)
        assertNull(gps.networkHorizontalAccuracy)
        assertNull(gps.fusedHorizontalAccuracy)

        assertEquals(35.0, network.providerHorizontalAccuracy()!!, 0.0)
        assertNull(network.gpsHorizontalAccuracy)
        assertNull(network.fusedHorizontalAccuracy)

        assertEquals(75.0, fused.providerHorizontalAccuracy()!!, 0.0)
        assertNull(fused.gpsHorizontalAccuracy)
        assertNull(fused.networkHorizontalAccuracy)
    }

    @Test
    fun missingProviderAccuracyIsNotBorrowedFromAnotherProvider() {
        val gps = row("gps", null)
        assertNull(gps.providerHorizontalAccuracy())
    }

    private fun row(provider: String, accuracy: Double?) = SplsRow(
        timeMs = 1_000L,
        provider = provider,
        lat = 30.0,
        lon = 70.0,
        gpsHorizontalAccuracy = if (provider == "gps") accuracy else null,
        networkHorizontalAccuracy = if (provider == "network") accuracy else null,
        fusedHorizontalAccuracy = if (provider == "fused") accuracy else null,
        vAcc = null,
        alt = null,
        msl = null,
        speed = null
    )
}
