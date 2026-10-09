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
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that records locations into [PointQueue] and uploads them periodically.
 *
 * With the adaptive profile, GPS runs at high accuracy while moving and drops to a low-power request when
 * activity recognition reports the phone as still (like Overland iOS pausing). A fix far from where the phone
 * went still switches back to high accuracy in case activity recognition is slow to notice. The other profiles
 * stay at high accuracy or low power. While low power, fixes other apps request (e.g. navigation) are also
 * received at no cost; a fast one switches back to high accuracy.
 *
 * With the Bluetooth trigger, a chosen device being connected (e.g. the car) forces high accuracy.
 * Failed uploads back off exponentially; nothing is tried without a network, and a network coming back
 * retries right away.
 * Inside a quiet zone, points are held back and location stays low power unless the phone is on a trip; the
 * last one held is sent first when recording resumes, so a trip starts where the phone was parked.
 *
 * The foreground notification shows the tracking [Status] as its icon, so the mode can be read from the status
 * bar or the always-on display. In a quiet zone it stays on a minimized channel, without a status bar icon on
 * phones that allow it (Android raises a foreground service's minimized channel to silent on some phones).
 */
class TrackingService : Service() {

    private lateinit var settings: Settings
    private lateinit var queue: PointQueue
    private lateinit var fused: FusedLocationProviderClient
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var uploadTask: ScheduledFuture<*>? = null
    /** Worker thread only */
    private val backoff = Backoff()
    private val handler = Handler(Looper.getMainLooper())

    /** Main thread only */
    private val trip = TripTracker()
    private val tripCheck = object : Runnable {
        override fun run() {
            // Also catches location being turned off and points waiting too long
            if (trip.tick(System.currentTimeMillis(), Motion.current)) updateNotification() else refreshStatus()
            handler.postDelayed(this, 60_000)
        }
    }
    /** Status of the notification last posted (main thread) */
    private var shownStatus: Status? = null
    /** When the queue last went from empty to non-empty; null while empty (written on the worker thread) */
    @Volatile private var pendingSince: Long? = null

    /** null until the first location request is made */
    private var lowPower: Boolean? = null
    private var stillAnchor: Location? = null
    private var lastLocation: Location? = null
    /** Movement seen while activity recognition still says "stationary" */
    private var movedWhileStill = false
    private lateinit var bluetooth: BluetoothTrigger
    private var zones: List<Zone> = emptyList()
    /** Last point held back in a quiet zone (Overland JSON) */
    private var held: String? = null

    private val activityIntent by lazy {
        PendingIntent.getBroadcast(
            this, 0, Intent(this, ActivityReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            try {
                worker.execute {
                    backoff.reset()
                    upload()
                }
            } catch (e: RejectedExecutionException) {
                // Service stopping
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    /** Fixes requested by other apps, received while low power */
    private val passiveCallback = object : LocationCallback() {
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
            Settings.KEY_BT_TRIGGER, Settings.KEY_BT_DEVICES -> bluetooth.refresh()
            Settings.KEY_ZONES -> zones = settings.zones
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        queue = PointQueue.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        bluetooth = BluetoothTrigger(this, settings) {
            // Connected-device lookups answer asynchronously, possibly after the service stopped
            if (running) {
                btDevice = bluetooth.deviceName
                applyLocationRequest()
                refreshStatus()
            }
        }
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
            Motion.listener = {
                applyLocationRequest()
                refreshStatus()
            }
            zones = settings.zones
            bluetooth.start()
            applyLocationRequest()
            startActivityUpdates()
            scheduleUpload()
            worker.execute { if (queue.count() > 0) pendingSince = System.currentTimeMillis() }
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            settings.registerListener(settingsListener)
        }
        if (intent?.action == ACTION_UPLOAD) worker.execute { upload(force = true) }

        return START_STICKY
    }

    @SuppressLint("MissingPermission") // checked by hasActivityPermission()
    override fun onDestroy() {
        if (running) {
            // only started once running
            bluetooth.stop()
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        running = false
        currentTrip = null
        highAccuracy = null
        btDevice = null
        quietZone = null
        settings.unregisterListener(settingsListener)
        handler.removeCallbacks(tripCheck)
        Motion.listener = null
        fused.removeLocationUpdates(locationCallback)
        fused.removeLocationUpdates(passiveCallback)
        if (hasActivityPermission(this)) {
            ActivityRecognition.getClient(this).removeActivityUpdates(activityIntent)
        }
        worker.execute { upload(force = true) } // flush what's left
        worker.shutdown()
        super.onDestroy()
    }

    /** Uploads every upload interval, rescheduled when the interval changes. */
    private fun scheduleUpload() {
        uploadTask?.cancel(false)
        val interval = settings.uploadIntervalSec.toLong()
        uploadTask = worker.scheduleWithFixedDelay(::upload, interval, interval, TimeUnit.SECONDS)
    }

    /**
     * (Re)requests locations with the accuracy matching the profile, current motion, Bluetooth trigger and quiet
     * zone. Points held in a quiet zone aren't sent, so it's low power there whatever the profile, until a trip
     * starts, the phone is in a vehicle or a fix lands outside the zone.
     */
    @SuppressLint("MissingPermission")
    private fun applyLocationRequest() {
        if (Motion.current != "stationary") movedWhileStill = false
        val inVehicle = Motion.current == "driving" || Motion.current == "cycling"
        val wantLowPower = !bluetooth.active && (quietZone != null && !inVehicle || when (settings.accuracyProfile) {
            Settings.PROFILE_HIGH -> false
            Settings.PROFILE_LOW -> true
            else -> Motion.current == "stationary" && !movedWhileStill
        })
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
        fused.removeLocationUpdates(passiveCallback)
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            if (wantLowPower) {
                val passive = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 10_000)
                    .setMinUpdateIntervalMillis(5_000)
                    .build()
                fused.requestLocationUpdates(passive, passiveCallback, Looper.getMainLooper())
            }
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
        // The same fix can come through both the main and the passive request
        if (location.time <= (lastLocation?.time ?: 0)) return
        lastLocation = location

        val anchor = stillAnchor
        val adaptive = settings.accuracyProfile == Settings.PROFILE_AUTO
        val fast = location.hasSpeed() && location.speed >= MOVING_SPEED_MS && location.accuracy <= 50
        if (adaptive && lowPower == true && (fast || anchor != null && location.accuracy < 100 && location.distanceTo(anchor) > 200)) {
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

        val zone = zones.firstOrNull { it.contains(location.latitude, location.longitude, location.accuracy) }
        val hold = zone != null && !trip.active && !bluetooth.active
        val wasQuiet = quietZone != null
        quietZone = if (hold) zone?.name else null
        if (hold != wasQuiet) applyLocationRequest()
        refreshStatus()
        val release = if (hold) null else held
        held = if (hold) feature else null

        settings.lastLocationAt = location.time
        if (Motion.current != null) settings.lastMotion = Motion.current
        settings.lastLocationText = String.format(
            Locale.US, "%.5f, %.5f ±%.0f m%s", location.latitude, location.longitude, location.accuracy,
            if (location.hasSpeed()) String.format(Locale.US, ", %.0f km/h", location.speed * 3.6) else "",
        )

        if (hold) return
        worker.execute {
            release?.let(queue::add)
            queue.add(feature)
            if (pendingSince == null) pendingSince = System.currentTimeMillis()
            if (queue.count() >= settings.batchSize) upload()
        }
    }

    /**
     * Sends what's queued (worker thread). No request at all when nothing was recorded (e.g. in a quiet zone),
     * without a network, or while backing off after a failure unless [force]d.
     */
    private fun upload(force: Boolean = false) {
        if (queue.count() == 0L || !online()) return
        val now = System.currentTimeMillis()
        if (settings.lastUploadOk) backoff.reset() // e.g. "Send now" worked since the last failure
        if (!force && !backoff.ready(now)) return
        try {
            Uploader.uploadAll(this)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
        }
        if (queue.count() == 0L) pendingSince = null
        if (settings.lastUploadOk) {
            backoff.reset()
        } else {
            backoff.onFailure(now)
            Log.i(TAG, "Upload failed, next try in ${(backoff.retryAtMs - now) / 1000} s")
        }
        handler.post(::updateNotification)
    }

    private fun online(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    /** What the phone is doing, shown as the notification icon; the first that applies wins. */
    enum class Status(@DrawableRes val icon: Int) {
        LOCATION_OFF(R.drawable.ic_status_location_off),
        /** On a trip, or a Bluetooth trigger device is connected */
        CAR(R.drawable.ic_status_car),
        /** Points have been waiting longer than [PROBLEM_AFTER_MS] */
        UPLOAD_PROBLEM(R.drawable.ic_status_upload_problem),
        QUIET_ZONE(R.drawable.ic_status_home),
        PARKED(R.drawable.ic_status_parked),
        TRACKING(R.drawable.ic_notification),
    }

    private fun status(): Status {
        val now = System.currentTimeMillis()
        return when {
            !LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java)) -> Status.LOCATION_OFF
            trip.active || bluetooth.active -> Status.CAR
            pendingSince?.let { now - it >= PROBLEM_AFTER_MS } == true -> Status.UPLOAD_PROBLEM
            quietZone != null -> Status.QUIET_ZONE
            Motion.current == "stationary" && !movedWhileStill -> Status.PARKED
            else -> Status.TRACKING
        }
    }

    /** Reposts the notification when the status changed (main thread). */
    private fun refreshStatus() {
        if (status() != shownStatus) updateNotification()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        // Quiet zone: minimized, no status bar icon where the phone allows it. Status: status bar and always-on
        // display icon (silent channels are left off the always-on display on some phones), with no sound.
        // Neither counts as an app icon badge: it's the ongoing service notification, it can't be dismissed.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_IDLE, getString(R.string.channel_idle), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.deleteNotificationChannel(CHANNEL_TRIP_OLD) // before 1.3.0
        nm.deleteNotificationChannel(CHANNEL_IDLE_OLD) // before 1.3.2, showed a badge
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val status = status()
        shownStatus = status
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val time = DateFormat.getTimeFormat(this)
        val lastUpload = if (settings.lastUploadAt > 0) {
            getString(R.string.idle_text, time.format(settings.lastUploadAt), settings.lastUploadResult)
        } else getString(R.string.idle_text_initial)
        val channel = if (status == Status.QUIET_ZONE || !settings.tripNotification) CHANNEL_IDLE else CHANNEL_STATUS
        val builder = NotificationCompat.Builder(this, channel).setShowWhen(false)

        when (status) {
            Status.LOCATION_OFF -> builder
                .setContentTitle(getString(R.string.status_location_off))
                .setContentText(getString(R.string.status_location_off_text))
            Status.CAR -> if (trip.active) {
                builder
                    .setContentTitle(getString(R.string.trip_in_progress))
                    .setContentText(getString(
                        R.string.trip_text,
                        String.format(Locale.getDefault(), "%.1f", trip.distanceM / 1000),
                        time.format(trip.startMs),
                    ))
                    .setWhen(trip.startMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true) // live elapsed time
            } else {
                builder
                    .setContentTitle(getString(R.string.status_in_car))
                    .setContentText(getString(R.string.status_in_car_text, bluetooth.deviceName.orEmpty()))
            }
            Status.UPLOAD_PROBLEM -> builder
                .setContentTitle(getString(R.string.status_upload_problem))
                .setContentText(getString(
                    R.string.status_upload_problem_text,
                    time.format(pendingSince ?: System.currentTimeMillis()),
                    if (online()) settings.lastUploadResult else getString(R.string.status_no_network),
                ))
            Status.QUIET_ZONE -> builder
                .setContentTitle(getString(R.string.status_quiet_zone, quietZone.orEmpty()))
                .setContentText(lastUpload)
            Status.PARKED -> builder
                .setContentTitle(getString(R.string.status_parked))
                .setContentText(lastUpload)
            Status.TRACKING -> builder
                .setContentTitle(getString(R.string.status_tracking))
                .setContentText(lastUpload)
        }

        return builder
            .setSmallIcon(status.icon)
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
        private const val CHANNEL_IDLE = "quiet"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_TRIP_OLD = "trip"
        private const val CHANNEL_IDLE_OLD = "idle"
        private const val PROBLEM_AFTER_MS = 15 * 60_000L
        private const val NOTIFICATION_ID = 1
        private const val MOVING_SPEED_MS = 5f
        const val ACTION_UPLOAD = "io.github.happytechca.overland.UPLOAD"

        @Volatile var running = false
            private set

        /** Whether the current location request is high accuracy; null when not running. */
        @Volatile var highAccuracy: Boolean? = null
            private set

        /** Name of the connected Bluetooth trigger device; null when none or not running. */
        @Volatile var btDevice: String? = null
            private set

        /** Name of the quiet zone points are being held back in; null when recording or not running. */
        @Volatile var quietZone: String? = null
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
