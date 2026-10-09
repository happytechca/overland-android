package io.github.happytechca.overland

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that records locations into [PointQueue] and uploads them periodically.
 *
 * With the adaptive profile, GPS runs at high accuracy while moving and drops to a low-power request when
 * activity recognition reports the phone as still (like Overland iOS pausing). A fix far from where the phone
 * went still switches back to high accuracy in case activity recognition is slow to notice. The other profiles
 * stay at high accuracy or low power.
 *
 * The foreground notification is minimized while idle and becomes "Trip in progress" (timer + distance)
 * while [TripTracker] thinks the phone is on a trip.
 */
class TrackingService : Service() {

    private lateinit var settings: Settings
    private lateinit var queue: PointQueue
    private lateinit var fused: FusedLocationProviderClient
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var uploadTask: ScheduledFuture<*>? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Main thread only */
    private val trip = TripTracker()
    private val tripCheck = object : Runnable {
        override fun run() {
            if (trip.tick(System.currentTimeMillis(), Motion.current)) updateNotification()
            handler.postDelayed(this, 60_000)
        }
    }

    /** null until the first location request is made */
    private var lowPower: Boolean? = null
    private var stillAnchor: Location? = null
    private var lastLocation: Location? = null
    /** Movement seen while activity recognition still says "stationary" */
    private var movedWhileStill = false

    private val activityIntent by lazy {
        PendingIntent.getBroadcast(
            this, 0, Intent(this, ActivityReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    /** Settings changed from the Settings screen while running (main thread) */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Settings.KEY_PROFILE -> applyLocationRequest()
            Settings.KEY_UPLOAD_INTERVAL -> scheduleUpload()
            Settings.KEY_TRIP_NOTIFICATION -> updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        queue = PointQueue.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission(this)) {
            Log.w(TAG, "No location permission, not starting")
            stopSelf()
            return START_NOT_STICKY
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // e.g. started from the background without "Allow all the time" location access
            Log.e(TAG, "Cannot start in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            currentTrip = trip
            handler.postDelayed(tripCheck, 60_000)
            Motion.listener = { applyLocationRequest() }
            applyLocationRequest()
            startActivityUpdates()
            scheduleUpload()
            settings.registerListener(settingsListener)
        }
        if (intent?.action == ACTION_UPLOAD) worker.execute(::upload)

        return START_STICKY
    }

    @SuppressLint("MissingPermission") // checked by hasActivityPermission()
    override fun onDestroy() {
        running = false
        currentTrip = null
        highAccuracy = null
        settings.unregisterListener(settingsListener)
        handler.removeCallbacks(tripCheck)
        Motion.listener = null
        fused.removeLocationUpdates(locationCallback)
        if (hasActivityPermission(this)) {
            ActivityRecognition.getClient(this).removeActivityUpdates(activityIntent)
        }
        worker.execute(::upload) // flush what's left
        worker.shutdown()
        super.onDestroy()
    }

    /** Uploads every upload interval, rescheduled when the interval changes. */
    private fun scheduleUpload() {
        uploadTask?.cancel(false)
        val interval = settings.uploadIntervalSec.coerceAtLeast(15).toLong()
        uploadTask = worker.scheduleWithFixedDelay(::upload, interval, interval, TimeUnit.SECONDS)
    }

    /** (Re)requests locations with the accuracy matching the profile and current motion. */
    @SuppressLint("MissingPermission")
    private fun applyLocationRequest() {
        if (Motion.current != "stationary") movedWhileStill = false
        val wantLowPower = when (settings.accuracyProfile) {
            Settings.PROFILE_HIGH -> false
            Settings.PROFILE_LOW -> true
            else -> Motion.current == "stationary" && !movedWhileStill
        }
        if (wantLowPower == lowPower) return
        lowPower = wantLowPower
        highAccuracy = !wantLowPower
        stillAnchor = if (wantLowPower) lastLocation else null

        val request = if (wantLowPower) {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000)
                .setMinUpdateIntervalMillis(60_000)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000)
                .setMinUpdateIntervalMillis(5_000)
                .build()
        }
        fused.removeLocationUpdates(locationCallback)
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission revoked", e)
            stopSelf()
        }
        Log.i(TAG, if (wantLowPower) "Low-power location (still)" else "High-accuracy location")
    }

    @SuppressLint("MissingPermission")
    private fun startActivityUpdates() {
        if (!hasActivityPermission(this)) return
        ActivityRecognition.getClient(this)
            .requestActivityUpdates(20_000, activityIntent)
            .addOnFailureListener { Log.w(TAG, "Activity recognition unavailable", it) }
    }

    private fun onLocation(location: Location) {
        lastLocation = location

        val anchor = stillAnchor
        val adaptive = settings.accuracyProfile == Settings.PROFILE_AUTO
        if (adaptive && lowPower == true && anchor != null && location.accuracy < 100 && location.distanceTo(anchor) > 200) {
            movedWhileStill = true
            applyLocationRequest()
        } else if (lowPower == true && anchor == null) {
            stillAnchor = location
        }

        val (batteryLevel, batteryState) = battery()
        val point = Point(
            latitude = location.latitude,
            longitude = location.longitude,
            timeMs = location.time,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speed = if (location.hasSpeed()) location.speed else null,
            horizontalAccuracy = if (location.hasAccuracy()) location.accuracy else null,
            verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
            motion = Motion.current,
            batteryLevel = batteryLevel,
            batteryState = batteryState,
            deviceId = settings.deviceId.trim(),
        )
        val feature = OverlandPayload.feature(point).toString()
        if (trip.onPoint(point)) updateNotification()

        settings.lastLocationAt = location.time
        if (Motion.current != null) settings.lastMotion = Motion.current
        settings.lastLocationText = String.format(
            Locale.US, "%.5f, %.5f ±%.0f m%s", location.latitude, location.longitude, location.accuracy,
            if (location.hasSpeed()) String.format(Locale.US, ", %.0f km/h", location.speed * 3.6) else "",
        )

        worker.execute {
            queue.add(feature)
            if (queue.count() >= settings.batchSize) upload()
        }
    }

    private fun upload() {
        try {
            Uploader.uploadAll(this)
            handler.post(::updateNotification)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
        }
    }

    private fun battery(): Pair<Float?, String> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to "unknown"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val state = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "unplugged"
            else -> "unknown"
        }
        return (if (level >= 0 && scale > 0) level.toFloat() / scale else null) to state
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        // Idle: collapsed in the shade, no status bar icon. Trip: status bar icon, still silent.
        nm.createNotificationChannel(NotificationChannel(CHANNEL_IDLE, getString(R.string.channel_idle), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TRIP, getString(R.string.channel_trip), NotificationManager.IMPORTANCE_LOW))
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (trip.active && settings.tripNotification) {
            NotificationCompat.Builder(this, CHANNEL_TRIP)
                .setContentTitle(getString(R.string.trip_in_progress))
                .setContentText(getString(
                    R.string.trip_text,
                    String.format(Locale.getDefault(), "%.1f", trip.distanceM / 1000),
                    DateFormat.getTimeFormat(this).format(trip.startMs),
                ))
                .setWhen(trip.startMs)
                .setShowWhen(true)
                .setUsesChronometer(true) // live elapsed time
        } else {
            val text = if (settings.lastUploadAt > 0) {
                getString(
                    R.string.idle_text,
                    DateFormat.getTimeFormat(this).format(settings.lastUploadAt),
                    settings.lastUploadResult,
                )
            } else getString(R.string.idle_text_initial)
            NotificationCompat.Builder(this, CHANNEL_IDLE)
                .setContentTitle(getString(R.string.idle_title))
                .setContentText(text)
                .setShowWhen(false)
        }

        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.brand))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_IDLE = "idle"
        private const val CHANNEL_TRIP = "trip"
        private const val NOTIFICATION_ID = 1
        const val ACTION_UPLOAD = "io.github.happytechca.overland.UPLOAD"

        @Volatile var running = false
            private set

        /** Whether the current location request is high accuracy; null when not running. */
        @Volatile var highAccuracy: Boolean? = null
            private set

        /** The running service's trip state, for the main screen (main thread only). */
        var currentTrip: TripTracker? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }

        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasActivityPermission(context: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }
}
