package io.github.happytechca.overland

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-phone guess of "trip in progress", for the notification only — the server receives the raw points and
 * is free to build trips its own way. A stop is a stay within [stopRadiusM] for [stopMs], or [stopMs] of
 * stationary/walking motion; distance only counts points accurate to [maxDistanceAccuracyM].
 */
class TripTracker(
    private val stopMs: Long = 5 * 60_000,
    private val stopRadiusM: Double = 150.0,
    private val movingSpeedMs: Float = 5f,
    private val maxDistanceAccuracyM: Float = 50f,
) {
    var active = false
        private set
    var startMs = 0L
        private set
    var distanceM = 0.0
        private set

    private var last: Point? = null
    /** First point of the current stay: the trip ends when we're still within stopRadiusM of it after stopMs */
    private var anchor: Point? = null
    /** When motion turned to stationary/walking/running during the trip */
    private var stillSince: Long? = null

    /** Returns true when the state or distance changed. */
    fun onPoint(p: Point): Boolean {
        val accurate = p.horizontalAccuracy == null || p.horizontalAccuracy <= maxDistanceAccuracyM
        trackMotion(p.motion, p.timeMs)

        if (!active) {
            val inVehicle = p.motion == "driving" || p.motion == "cycling"
            val fast = accurate && (p.speed ?: 0f) >= movingSpeedMs
            if (!inVehicle && !fast) return false
            active = true
            startMs = p.timeMs
            distanceM = 0.0
            last = if (accurate) p else null
            anchor = p
            stillSince = null
            return true
        }

        if (accurate) {
            last?.let { distanceM += distance(it, p) }
            last = p
        }
        val a = anchor
        if (a == null || distance(a, p) > stopRadiusM) {
            anchor = p
        } else if (p.timeMs - a.timeMs >= stopMs && (p.speed ?: 0f) < movingSpeedMs) {
            end()
        }
        return true
    }

    /**
     * Periodic check, for when no points arrive (GPS in low-power mode while parked).
     * Returns true when the trip ended.
     */
    fun tick(nowMs: Long, motion: String?): Boolean {
        if (!active) return false
        trackMotion(motion, nowMs)
        val stayedPut = anchor?.let { nowMs - it.timeMs >= stopMs && motion != "driving" && motion != "cycling" } ?: false
        val stillLongEnough = stillSince?.let { nowMs - it >= stopMs } ?: false
        if (stayedPut || stillLongEnough) {
            end()
            return true
        }
        return false
    }

    private fun trackMotion(motion: String?, timeMs: Long) {
        if (motion in STILL_MOTIONS) {
            if (stillSince == null) stillSince = timeMs
        } else if (motion != null) {
            stillSince = null
        }
    }

    private fun end() {
        active = false
        last = null
        anchor = null
        stillSince = null
    }

    companion object {
        private val STILL_MOTIONS = setOf("stationary", "walking", "running")

        fun distance(a: Point, b: Point): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLng = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2).pow(2)
            return 2 * 6_371_000 * asin(minOf(1.0, sqrt(h)))
        }
    }
}
