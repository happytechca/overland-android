package io.github.happytechca.overland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/** Latest activity from Google's activity recognition, as an Overland motion value. */
object Motion {
    @Volatile var current: String? = null
        private set

    /** Called on the main thread when [current] changes. */
    var listener: (() -> Unit)? = null

    fun update(motion: String) {
        if (motion == current) return
        current = motion
        listener?.invoke()
    }
}

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val motion = when (result.mostProbableActivity.type) {
            DetectedActivity.IN_VEHICLE -> "driving"
            DetectedActivity.ON_BICYCLE -> "cycling"
            DetectedActivity.ON_FOOT, DetectedActivity.WALKING -> "walking"
            DetectedActivity.RUNNING -> "running"
            DetectedActivity.STILL -> "stationary"
            else -> return // TILTING / UNKNOWN: keep the previous value
        }
        Motion.update(motion)
    }
}
