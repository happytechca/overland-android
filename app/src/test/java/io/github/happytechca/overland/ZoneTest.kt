package io.github.happytechca.overland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneTest {

    private val home = Zone("Home", 45.5, -73.5, 100)

    /** [northM] metres north of home */
    private fun north(m: Double) = 45.5 + m / 111_195.0

    @Test
    fun containsPointsWithinRadius() {
        assertTrue(home.contains(45.5, -73.5, 5f))
        assertTrue(home.contains(north(90.0), -73.5, 5f))
        assertFalse(home.contains(north(150.0), -73.5, 5f))
    }

    @Test
    fun accuracyWidensTheZoneByAtMostOneRadius() {
        assertTrue(home.contains(north(150.0), -73.5, 60f))
        assertTrue(home.contains(north(190.0), -73.5, 1500f))
        assertFalse(home.contains(north(250.0), -73.5, 1500f))
        assertFalse(home.contains(north(150.0), -73.5, null))
    }

    @Test
    fun jsonRoundTrip() {
        val zones = listOf(home, Zone("Office “A”", 45.6, -73.4, 500))
        assertEquals(zones, Zone.fromJson(Zone.toJson(zones)))
        assertEquals(emptyList<Zone>(), Zone.fromJson(null))
        val devices = listOf(BtDevice("00:11:22:33:44:55", "My Car"))
        assertEquals(devices, BtDevice.fromJson(BtDevice.toJson(devices)))
    }
}
