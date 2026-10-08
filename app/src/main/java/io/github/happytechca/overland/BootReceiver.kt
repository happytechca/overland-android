package io.github.happytechca.overland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Resumes tracking after a reboot or an app update if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Settings(context).trackingEnabled || !TrackingService.hasLocationPermission(context)) return
        try {
            TrackingService.start(context)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Cannot resume tracking", e)
        }
    }
}
