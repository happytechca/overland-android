package io.github.happytechca.overland

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** The checks behind the Home health banner and the Diagnostics screen. */
enum class Check(@StringRes val title: Int) {
    PRECISE(R.string.check_precise),
    BACKGROUND(R.string.check_background),
    ACTIVITY(R.string.check_activity),
    NOTIFICATIONS(R.string.check_notifications),
    BATTERY(R.string.check_battery),
    /** Only while the Bluetooth trigger is on */
    BLUETOOTH(R.string.check_bluetooth),
}

object Health {

    /** Checks that apply to this Android version and the current settings */
    fun checks(context: Context): List<Check> = Check.entries.filter {
        when (it) {
            Check.BACKGROUND, Check.ACTIVITY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            Check.BLUETOOTH -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Settings(context).btTrigger
            else -> true
        }
    }

    fun passes(context: Context, check: Check): Boolean = when (check) {
        Check.PRECISE -> has(context, Manifest.permission.ACCESS_FINE_LOCATION)
        Check.BACKGROUND -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Check.ACTIVITY -> TrackingService.hasActivityPermission(context)
        Check.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        Check.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        Check.BLUETOOTH -> BluetoothTrigger.hasPermission(context)
    }

    fun failing(context: Context): List<Check> = checks(context).filterNot { passes(context, it) }

    /** Only approximate location was granted (Android 12+ lets the user pick) */
    fun coarseOnly(context: Context) =
        !has(context, Manifest.permission.ACCESS_FINE_LOCATION) && has(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun playServicesAvailable(context: Context) =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** Manufacturer with extra background limits, as its dontkillmyapp.com slug and display name; null if none. */
    fun oemVendor(): Pair<String, String>? {
        val slug = Build.MANUFACTURER.lowercase().replace(" ", "-")
        return OEMS[slug]?.let { slug to it }
    }

    fun issuesTitle(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.issues_title, count, count)

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val OEMS = mapOf(
        "samsung" to "Samsung", "xiaomi" to "Xiaomi", "huawei" to "Huawei", "honor" to "Honor",
        "oneplus" to "OnePlus", "oppo" to "Oppo", "realme" to "Realme", "vivo" to "Vivo", "meizu" to "Meizu",
        "asus" to "Asus", "sony" to "Sony", "lenovo" to "Lenovo", "nokia" to "Nokia", "tecno" to "Tecno",
        "infinix" to "Infinix", "wiko" to "Wiko", "blackview" to "Blackview", "unihertz" to "Unihertz",
    )
}
