package io.github.happytechca.overland

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

/** One GPS fix, independent of android.location so it can be unit-tested. */
data class Point(
    val latitude: Double,
    val longitude: Double,
    val timeMs: Long,
    val altitude: Double? = null,
    /** m/s */
    val speed: Float? = null,
    /** m */
    val horizontalAccuracy: Float? = null,
    val verticalAccuracy: Float? = null,
    /** "driving", "walking", "running", "cycling", "stationary" or null when unknown */
    val motion: String? = null,
    /** 0..1 */
    val batteryLevel: Float? = null,
    /** "unknown", "unplugged", "charging" or "full", as in Overland iOS */
    val batteryState: String = "unknown",
    val deviceId: String = "",
)

/** Builds the Overland (iOS) request format: {"locations": [GeoJSON Feature, ...]}. */
object OverlandPayload {

    fun feature(p: Point): JSONObject {
        val props = JSONObject()
            // Whole seconds in UTC, e.g. 2026-10-07T13:35:22Z
            .put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(p.timeMs / 1000)))
            // Overland iOS uses -1 for "unknown"
            .put("altitude", p.altitude?.let { Math.round(it) } ?: 0)
            .put("speed", p.speed?.let { round1(it) } ?: -1)
            .put("horizontal_accuracy", p.horizontalAccuracy?.let { Math.round(it) } ?: -1)
            .put("vertical_accuracy", p.verticalAccuracy?.let { Math.round(it) } ?: -1)
            .put("motion", JSONArray().apply { p.motion?.let { put(it) } })
            .put("battery_state", p.batteryState)
            .put("battery_level", p.batteryLevel?.let { round2(it) } ?: -1)
            .put("device_id", p.deviceId)

        return JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject()
                .put("type", "Point")
                .put("coordinates", JSONArray().put(p.longitude).put(p.latitude)))
            .put("properties", props)
    }

    /** Request body from already-serialized features. */
    fun batch(features: List<String>): String =
        features.joinToString(",", prefix = "{\"locations\":[", postfix = "]}")

    private fun round1(v: Float) = Math.round(v * 10) / 10.0
    private fun round2(v: Float) = Math.round(v * 100) / 100.0
}
