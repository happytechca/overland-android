package io.github.happytechca.overland

import android.content.Context
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

    var uploadIntervalSec: Int
        get() = prefs.getInt("upload_interval", 60)
        set(v) = prefs.edit { putInt("upload_interval", v) }

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

    var lastUploadAt: Long
        get() = prefs.getLong("last_upload_at", 0)
        set(v) = prefs.edit { putLong("last_upload_at", v) }

    var lastUploadResult: String
        get() = prefs.getString("last_upload_result", "")!!
        set(v) = prefs.edit { putString("last_upload_result", v) }

    companion object {
        const val DEFAULT_URL = ""
        const val DEFAULT_DEVICE_ID = ""
        const val BATCH_SIZE = 200
    }
}
