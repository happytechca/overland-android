package io.github.happytechca.overland

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OverlandPayloadTest {

    private val point = Point(
        latitude = 45.5826,
        longitude = -73.4834,
        timeMs = 1_791_380_122_789, // 2026-10-07T13:35:22.789Z
        altitude = 30.4,
        speed = 13.87f,
        horizontalAccuracy = 4.6f,
        verticalAccuracy = null,
        motion = "driving",
        batteryLevel = 0.853f,
        batteryState = "unplugged",
        deviceId = "android-test",
    )

    @Test
    fun featureMatchesOverlandFormat() {
        val f = OverlandPayload.feature(point)
        assertEquals("Feature", f.getString("type"))
        assertEquals("Point", f.getJSONObject("geometry").getString("type"))
        val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(-73.4834, coords.getDouble(0), 0.0) // lng first
        assertEquals(45.5826, coords.getDouble(1), 0.0)

        val p = f.getJSONObject("properties")
        assertEquals("2026-10-07T13:35:22Z", p.getString("timestamp"))
        assertEquals(13.9, p.getDouble("speed"), 0.0)
        assertEquals(5, p.getInt("horizontal_accuracy"))
        assertEquals(-1, p.getInt("vertical_accuracy"))
        assertEquals(30, p.getInt("altitude"))
        assertEquals("driving", p.getJSONArray("motion").getString(0))
        assertEquals(0.85, p.getDouble("battery_level"), 0.0)
        assertEquals("unplugged", p.getString("battery_state"))
        assertEquals("android-test", p.getString("device_id"))
    }

    @Test
    fun unknownValuesUseOverlandConventions() {
        val p = OverlandPayload.feature(Point(45.0, -73.0, 0, motion = null)).getJSONObject("properties")
        assertEquals(-1, p.getInt("speed"))
        assertEquals(-1, p.getInt("horizontal_accuracy"))
        assertEquals(0, p.getJSONArray("motion").length())
        assertEquals("1970-01-01T00:00:00Z", p.getString("timestamp"))
    }

    @Test
    fun batchWrapsFeaturesInLocations() {
        val features = listOf(OverlandPayload.feature(point).toString(), OverlandPayload.feature(point.copy(motion = "stationary")).toString())
        val body = JSONObject(OverlandPayload.batch(features))
        assertEquals(2, body.getJSONArray("locations").length())
        assertEquals(0, JSONObject(OverlandPayload.batch(emptyList())).getJSONArray("locations").length())

        // Sample request for checking against the server-side parser
        File("build/sample-payload.json").apply { parentFile?.mkdirs() }.writeText(OverlandPayload.batch(features))
    }
}
