package io.github.happytechca.overland

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** User settings plus the last-known status shown on the main screen. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", DEFAULT_URL)!!
        set(v) = prefs.edit { putString("url", v) }

    var token: String
        get() = prefs.getString("token", "")!!
        set(v) = prefs.edit { putString("token", v) }

    var deviceId: String
        get() = prefs.getString("device_id", DEFAULT_DEVICE_ID)!!
        set(v) = prefs.edit { putString("device_id", v) }

    /** At least 30 s (15 s was an option before 1.2.0) */
    var uploadIntervalSec: Int
        get() = prefs.getInt(KEY_UPLOAD_INTERVAL, 60).coerceAtLeast(30)
        set(v) = prefs.edit { putInt(KEY_UPLOAD_INTERVAL, v) }

    /** Most points per request */
    var batchSize: Int
        get() = prefs.getInt("batch_size", 200)
        set(v) = prefs.edit { putInt("batch_size", v) }

    /** [PROFILE_AUTO], [PROFILE_HIGH] or [PROFILE_LOW] */
    var accuracyProfile: String
        get() = prefs.getString(KEY_PROFILE, PROFILE_AUTO)!!
        set(v) = prefs.edit { putString(KEY_PROFILE, v) }

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", true)
        set(v) = prefs.edit { putBoolean("start_on_boot", v) }

    /** Show the status icon (in the car, parked, tracking, problem); when off the notification stays minimized. */
    var tripNotification: Boolean
        get() = prefs.getBoolean(KEY_TRIP_NOTIFICATION, true)
        set(v) = prefs.edit { putBoolean(KEY_TRIP_NOTIFICATION, v) }

    /** High accuracy while one of [btDevices] is connected */
    var btTrigger: Boolean
        get() = prefs.getBoolean(KEY_BT_TRIGGER, false)
        set(v) = prefs.edit { putBoolean(KEY_BT_TRIGGER, v) }

    var btDevices: List<BtDevice>
        get() = BtDevice.fromJson(prefs.getString(KEY_BT_DEVICES, null))
        set(v) = prefs.edit { putString(KEY_BT_DEVICES, BtDevice.toJson(v)) }

    var zones: List<Zone>
        get() = Zone.fromJson(prefs.getString(KEY_ZONES, null))
        set(v) = prefs.edit { putString(KEY_ZONES, Zone.toJson(v)) }

    /** The manufacturer guide on dontkillmyapp.com was opened from Diagnostics */
    var oemReviewed: Boolean
        get() = prefs.getBoolean("oem_reviewed", false)
        set(v) = prefs.edit { putBoolean("oem_reviewed", v) }

    /** The user wants tracking on; the service is restarted after reboot / app update when set. */
    var trackingEnabled: Boolean
        get() = prefs.getBoolean("tracking_enabled", false)
        set(v) = prefs.edit { putBoolean("tracking_enabled", v) }

    var lastLocationAt: Long
        get() = prefs.getLong("last_location_at", 0)
        set(v) = prefs.edit { putLong("last_location_at", v) }

    var lastLocationText: String
        get() = prefs.getString("last_location_text", "")!!
        set(v) = prefs.edit { putString("last_location_text", v) }

    /** Motion of the last recorded point, shown while tracking is off */
    var lastMotion: String?
        get() = prefs.getString("last_motion", null)
        set(v) = prefs.edit { putString("last_motion", v) }

    var lastUploadAt: Long
        get() = prefs.getLong("last_upload_at", 0)
        set(v) = prefs.edit { putLong("last_upload_at", v) }

    var lastUploadResult: String
        get() = prefs.getString("last_upload_result", "")!!
        set(v) = prefs.edit { putString("last_upload_result", v) }

    var lastUploadOk: Boolean
        get() = prefs.getBoolean("last_upload_ok", true)
        set(v) = prefs.edit { putBoolean("last_upload_ok", v) }

    /** The listener must be kept referenced: SharedPreferences only holds it weakly. */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val DEFAULT_URL = ""
        const val DEFAULT_DEVICE_ID = ""

        const val KEY_UPLOAD_INTERVAL = "upload_interval"
        const val KEY_PROFILE = "accuracy_profile"
        const val KEY_TRIP_NOTIFICATION = "trip_notification"
        const val KEY_BT_TRIGGER = "bt_trigger"
        const val KEY_BT_DEVICES = "bt_devices"
        const val KEY_ZONES = "zones"

        const val PROFILE_AUTO = "auto"
        const val PROFILE_HIGH = "high"
        const val PROFILE_LOW = "low"
    }
}
