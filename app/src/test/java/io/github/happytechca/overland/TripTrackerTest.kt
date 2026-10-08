package io.github.happytechca.overland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackerTest {

    private val minute = 60_000L

    /** ~0.0009° latitude ≈ 100 m */
    private fun pt(min: Double, northM: Double, speed: Float? = null, motion: String? = null, accuracy: Float = 5f) =
        Point(45.5 + northM / 111_195.0, -73.5, (min * minute).toLong(), speed = speed, motion = motion, horizontalAccuracy = accuracy)

    @Test
    fun walkingAroundIsNotATrip() {
        val t = TripTracker()
        assertFalse(t.onPoint(pt(0.0, 0.0, speed = 1.4f, motion = "walking")))
        assertFalse(t.onPoint(pt(1.0, 80.0, speed = 1.4f, motion = "walking")))
        assertFalse(t.active)
    }

    @Test
    fun drivingStartsTripAndAccumulatesDistance() {
        val t = TripTracker()
        t.onPoint(pt(0.0, 0.0, speed = 12f, motion = "driving"))
        t.onPoint(pt(0.5, 400.0, speed = 13f, motion = "driving"))
        t.onPoint(pt(1.0, 800.0, speed = 13f, motion = "driving"))
        assertTrue(t.active)
        assertEquals(0L, t.startMs)
        assertEquals(800.0, t.distanceM, 1.0)
    }

    @Test
    fun fastGpsStartsTripWithoutActivityRecognition() {
        val t = TripTracker()
        assertTrue(t.onPoint(pt(0.0, 0.0, speed = 8f)))
        assertTrue(t.active)
    }

    @Test
    fun inaccuratePointsDontCountTowardsDistance() {
        val t = TripTracker()
        t.onPoint(pt(0.0, 0.0, speed = 12f, motion = "driving"))
        t.onPoint(pt(0.5, 2000.0, speed = 12f, motion = "driving", accuracy = 80f))
        t.onPoint(pt(1.0, 800.0, speed = 12f, motion = "driving"))
        assertEquals(800.0, t.distanceM, 1.0)
    }

    @Test
    fun redLightDoesNotEndTrip() {
        val t = TripTracker()
        t.onPoint(pt(0.0, 0.0, speed = 12f, motion = "driving"))
        t.onPoint(pt(1.0, 700.0, speed = 0f, motion = "stationary"))
        t.onPoint(pt(3.0, 705.0, speed = 0f, motion = "stationary"))
        assertFalse(t.tick(4 * minute, "stationary"))
        t.onPoint(pt(4.5, 1200.0, speed = 12f, motion = "driving"))
        assertFalse(t.tick(9 * minute, "driving"))
        assertTrue(t.active)
    }

    @Test
    fun fiveMinutesParkedEndsTripEvenWithoutNewPoints() {
        val t = TripTracker()
        t.onPoint(pt(0.0, 0.0, speed = 12f, motion = "driving"))
        t.onPoint(pt(10.0, 5000.0, speed = 0f, motion = "stationary"))
        assertFalse(t.tick(14 * minute, "stationary"))
        assertTrue(t.tick(15 * minute, "stationary"))
        assertFalse(t.active)
    }

    @Test
    fun stayingPutEndsTripOnNextPoint() {
        val t = TripTracker()
        t.onPoint(pt(0.0, 0.0, speed = 12f))
        t.onPoint(pt(10.0, 5000.0, speed = 0f))
        t.onPoint(pt(13.0, 5040.0, speed = 0f))
        t.onPoint(pt(15.5, 5020.0, speed = 0f))
        assertFalse(t.active)
    }

    @Test
    fun walkingAwayFromTheCarEndsTrip() {
        val t = TripTracker()
        t.onPoint(pt(0.0, 0.0, speed = 12f, motion = "driving"))
        t.onPoint(pt(10.0, 5000.0, speed = 1.4f, motion = "walking"))
        t.onPoint(pt(13.0, 5200.0, speed = 1.4f, motion = "walking"))
        assertTrue(t.tick(15 * minute, "walking"))
    }
}
