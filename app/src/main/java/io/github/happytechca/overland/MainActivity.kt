package io.github.happytechca.overland

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.github.happytechca.overland.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    /** Permissions already asked during the current "Start tracking" flow, so a denial isn't asked again. */
    private val asked = mutableSetOf<String>()
    private var starting = false
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { continueStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        // Edge-to-edge (enforced from Android 15): keep content clear of the status and navigation bars
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.url.setText(settings.url)
        binding.token.setText(settings.token)
        binding.deviceId.setText(settings.deviceId)
        binding.interval.setText(settings.uploadIntervalSec.toString())

        binding.toggle.setOnClickListener {
            if (TrackingService.running) {
                settings.trackingEnabled = false
                TrackingService.stop(this)
                handler.postDelayed(::refresh, 300)
            } else {
                saveSettings()
                starting = true
                asked.clear()
                continueStart()
            }
        }
        binding.upload.setOnClickListener { uploadNow() }
        binding.save.setOnClickListener {
            saveSettings()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
        binding.appSettings.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        binding.battery.setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    private fun saveSettings() {
        settings.url = binding.url.text.toString().trim()
        settings.token = binding.token.text.toString().trim()
        settings.deviceId = binding.deviceId.text.toString().trim()
        val interval = binding.interval.text.toString().toIntOrNull()?.coerceIn(15, 3600) ?: 60
        settings.uploadIntervalSec = interval
        binding.interval.setText(interval.toString())
    }

    /** Asks for each missing permission in turn, then starts the service. */
    private fun continueStart() {
        if (!starting) return

        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (Manifest.permission.ACCESS_FINE_LOCATION in asked) {
                starting = false
                Toast.makeText(this, R.string.location_required, Toast.LENGTH_LONG).show()
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
        saveSettings()
        binding.upload.isEnabled = false
        binding.upload.setText(R.string.uploading)
        Thread {
            val result = Uploader.uploadAll(applicationContext)
            runOnUiThread {
                binding.upload.isEnabled = true
                binding.upload.setText(R.string.upload_now)
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        val intent = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }

    private fun refresh() {
        val running = TrackingService.running
        binding.status.setText(if (running) R.string.tracking_on else R.string.tracking_off)
        binding.toggle.setText(if (running) R.string.stop_tracking else R.string.start_tracking)

        binding.details.text = buildString {
            append("Last location: ")
            append(if (settings.lastLocationAt > 0) "${ago(settings.lastLocationAt)}\n${settings.lastLocationText}" else "none yet")
            append("\nMotion: ${Motion.current ?: "unknown"}")
            val trip = TrackingService.currentTrip?.takeIf { it.active }
            append("\nTrip: ")
            append(trip?.let {
                String.format(Locale.getDefault(), "in progress, %.1f km since %s", it.distanceM / 1000,
                    android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(it.startMs))
            } ?: "none")
            append("\nQueued points: ${PointQueue.get(this@MainActivity).count()}")
            append("\nLast upload: ")
            append(if (settings.lastUploadAt > 0) "${ago(settings.lastUploadAt)} — ${settings.lastUploadResult}" else "never")
        }

        binding.checks.text = buildString {
            append(check("Location", has(Manifest.permission.ACCESS_FINE_LOCATION)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(check("Location all the time", has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
                append(check("Physical activity", has(Manifest.permission.ACTIVITY_RECOGNITION)))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append(check("Notifications", has(Manifest.permission.POST_NOTIFICATIONS)))
            }
            append(check("Battery unrestricted", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)))
        }.trimEnd()
    }

    private fun check(label: String, ok: Boolean) = "${if (ok) "✓" else "✗"}  $label\n"

    private fun ago(time: Long) =
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
}
