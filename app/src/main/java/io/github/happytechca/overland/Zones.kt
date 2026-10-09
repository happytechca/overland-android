package io.github.happytechca.overland

import org.json.JSONArray
import org.json.JSONObject

/**
 * A quiet zone (home, office…): points recorded inside it are held back unless the phone is on a trip,
 * so walking around inside sends nothing.
 */
data class Zone(val name: String, val latitude: Double, val longitude: Double, val radiusM: Int) {

    /** A fix counts as inside when its centre is within the radius plus its accuracy (at most one more radius). */
    fun contains(latitude: Double, longitude: Double, accuracyM: Float?): Boolean {
        val slack = minOf((accuracyM ?: 0f).toDouble(), radiusM.toDouble())
        return TripTracker.distance(this.latitude, this.longitude, latitude, longitude) <= radiusM + slack
    }

    companion object {
        fun toJson(zones: List<Zone>): String = JSONArray(
            zones.map { JSONObject().put("name", it.name).put("lat", it.latitude).put("lng", it.longitude).put("radius", it.radiusM) },
        ).toString()

        fun fromJson(json: String?): List<Zone> {
            if (json.isNullOrEmpty()) return emptyList()
            val array = JSONArray(json)
            return (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Zone(o.getString("name"), o.getDouble("lat"), o.getDouble("lng"), o.getInt("radius"))
            }
        }
    }
}

/** A paired Bluetooth device chosen for the Bluetooth trigger; the name is kept for display. */
data class BtDevice(val address: String, val name: String) {
    companion object {
        fun toJson(devices: List<BtDevice>): String =
            JSONArray(devices.map { JSONObject().put("address", it.address).put("name", it.name) }).toString()

        fun fromJson(json: String?): List<BtDevice> {
            if (json.isNullOrEmpty()) return emptyList()
            val array = JSONArray(json)
            return (0 until array.length()).map {
                val o = array.getJSONObject(it)
                BtDevice(o.getString("address"), o.getString("name"))
            }
        }
    }
}
