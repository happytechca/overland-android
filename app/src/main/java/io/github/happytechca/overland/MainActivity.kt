package io.github.happytechca.overland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import io.github.happytechca.overland.databinding.ActivityMainBinding
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Home: live status only. Server settings are in [SettingsActivity], permission checks in [DiagnosticsActivity]. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000) // the trip timer ticks every second
        }
    }

    /** Permissions already asked during the current "Start tracking" flow, so a denial isn't asked again. */
    private val asked = mutableSetOf<String>()
    private var starting = false
    private var sending = false
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { continueStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        edgeToEdge(binding.root)
        settings = Settings(this)

        binding.settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.health.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        binding.toggle.setOnClickListener {
            if (TrackingService.running) {
                settings.trackingEnabled = false
                TrackingService.stop(this)
                binding.root.snack(getString(R.string.tracking_stopped))
                handler.postDelayed(::refresh, 300)
            } else {
                starting = true
                asked.clear()
                continueStart()
            }
        }
        binding.upload.setOnClickListener { uploadNow() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    /** Asks for each missing permission in turn, then starts the service. */
    private fun continueStart() {
        if (!starting) return

        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (Manifest.permission.ACCESS_FINE_LOCATION in asked) {
                starting = false
                binding.root.snack(getString(R.string.location_required))
                return
            }
            ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && needs(Manifest.permission.POST_NOTIFICATIONS)) {
            ask(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && needs(Manifest.permission.ACTIVITY_RECOGNITION)) {
            ask(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && needs(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            asked += Manifest.permission.ACCESS_BACKGROUND_LOCATION
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.background_title)
                .setMessage(R.string.background_message)
                .setPositiveButton(R.string.continue_) { _, _ -> ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                .setNegativeButton(R.string.skip) { _, _ -> continueStart() }
                .setCancelable(false)
                .show()
            return
        }

        starting = false
        settings.trackingEnabled = true
        TrackingService.start(this)
        binding.root.snack(getString(R.string.tracking_started))
        handler.postDelayed(::refresh, 300)
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun needs(permission: String) = !has(permission) && permission !in asked

    private fun ask(vararg permissions: String) {
        asked += permissions
        permissionLauncher.launch(arrayOf(*permissions))
    }

    private fun uploadNow() {
        if (sending) return
        sending = true
        refresh()
        Thread {
            val result = Uploader.uploadAll(applicationContext)
            runOnUiThread {
                sending = false
                binding.root.snack(result)
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val running = TrackingService.running
        val root = binding.root

        // Health banner
        val failing = Health.failing(this)
        if (failing.isEmpty()) {
            val fg = getColor(R.color.on_ok_container)
            binding.health.setCardBackgroundColor(getColor(R.color.ok_container))
            binding.healthIcon.setImageResource(R.drawable.ic_verified_user)
            binding.healthIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.ok))
            binding.healthTitle.setText(R.string.ready_to_track)
            binding.healthTitle.setTextColor(fg)
            binding.healthText.setText(R.string.all_checks_passed)
            binding.healthText.setTextColor(getColor(R.color.on_ok_container_variant))
            binding.healthChevron.imageTintList = ColorStateList.valueOf(fg)
        } else {
            val fg = root.themeColor(MaterialR.attr.colorOnErrorContainer)
            binding.health.setCardBackgroundColor(root.themeColor(MaterialR.attr.colorErrorContainer))
            binding.healthIcon.setImageResource(R.drawable.ic_error)
            binding.healthIcon.imageTintList = ColorStateList.valueOf(root.themeColor(MaterialR.attr.colorError))
            binding.healthTitle.text = Health.issuesTitle(this, failing.size)
            binding.healthTitle.setTextColor(fg)
            binding.healthText.text = failing.joinToString(" · ") { getString(it.title) }
            binding.healthText.setTextColor(getColor(R.color.on_error_container_variant))
            binding.healthChevron.imageTintList = ColorStateList.valueOf(fg)
        }

        // Status card
        val high = TrackingService.highAccuracy ?: (settings.accuracyProfile != Settings.PROFILE_LOW)
        binding.statusDot.imageTintList = ColorStateList.valueOf(getColor(if (running) R.color.ok else R.color.dot_off))
        binding.status.setText(if (running) R.string.tracking_on else R.string.tracking_off)
        binding.mode.text = if (running) getString(if (high) R.string.mode_high else R.string.mode_low) else ""

        val motion = MotionUi.of(Motion.current ?: settings.lastMotion)
        binding.motionIcon.setImageResource(motion.icon)
        binding.motion.setText(motion.label)
        val btDevice = TrackingService.btDevice
        val quietZone = TrackingService.quietZone
        binding.motionSub.text = when {
            !running -> getString(R.string.motion_sub_off)
            btDevice != null -> getString(R.string.motion_sub_bt, btDevice)
            quietZone != null -> getString(R.string.motion_sub_zone, quietZone)
            high -> getString(R.string.motion_sub_high)
            else -> getString(R.string.motion_sub_low)
        }

        val trip = TrackingService.currentTrip?.takeIf { it.active }
        binding.trip.isVisible = trip != null
        binding.noTrip.isVisible = trip == null
        if (trip != null) {
            binding.tripSince.text = getString(R.string.trip_since, DateFormat.getTimeFormat(this).format(trip.startMs))
            binding.tripElapsed.text = clock(System.currentTimeMillis() - trip.startMs)
            binding.tripKm.text = getString(R.string.trip_km, String.format(Locale.getDefault(), "%.1f", trip.distanceM / 1000))
        }

        // Start / stop
        val (bg, fg) = if (running) {
            root.themeColor(MaterialR.attr.colorSecondaryContainer) to root.themeColor(MaterialR.attr.colorOnSecondaryContainer)
        } else {
            getColor(R.color.brand) to getColor(R.color.on_brand)
        }
        binding.toggle.setText(if (running) R.string.stop_tracking else R.string.start_tracking)
        binding.toggle.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play_arrow)
        binding.toggle.backgroundTintList = ColorStateList.valueOf(bg)
        binding.toggle.setTextColor(fg)
        binding.toggle.iconTint = ColorStateList.valueOf(fg)

        // Activity
        if (settings.lastLocationAt > 0) {
            binding.fixTitle.text = getString(R.string.last_fix, ago(settings.lastLocationAt))
            binding.fixText.text = settings.lastLocationText
        } else {
            binding.fixTitle.setText(R.string.last_fix_none)
            binding.fixText.setText(R.string.last_fix_none_sub)
        }

        val (icon, tint) = when {
            sending -> R.drawable.ic_cloud_sync to root.themeColor(MaterialR.attr.colorOnSurfaceVariant)
            settings.lastUploadAt == 0L -> R.drawable.ic_cloud_upload to root.themeColor(MaterialR.attr.colorOnSurfaceVariant)
            settings.lastUploadOk -> R.drawable.ic_cloud_done to getColor(R.color.ok)
            else -> R.drawable.ic_cloud_off to root.themeColor(MaterialR.attr.colorError)
        }
        binding.uploadIcon.setImageResource(icon)
        binding.uploadIcon.imageTintList = ColorStateList.valueOf(tint)
        if (settings.lastUploadAt > 0) {
            binding.uploadTitle.text = getString(R.string.last_upload, ago(settings.lastUploadAt))
            binding.uploadText.text = settings.lastUploadResult
        } else {
            binding.uploadTitle.setText(R.string.last_upload_none)
            binding.uploadText.setText(R.string.last_upload_none_sub)
        }

        val queued = PointQueue.get(this).count().toInt()
        binding.queueText.text =
            if (queued == 0) getString(R.string.queue_empty) else resources.getQuantityString(R.plurals.queue_waiting, queued, queued)
        binding.upload.setText(if (sending) R.string.sending else R.string.send_now)
        binding.upload.isEnabled = !sending
    }
}
