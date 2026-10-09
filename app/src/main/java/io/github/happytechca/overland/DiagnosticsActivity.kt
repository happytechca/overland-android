package io.github.happytechca.overland

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.happytechca.overland.databinding.ActivityDiagnosticsBinding
import io.github.happytechca.overland.databinding.ItemCheckBinding

/** Every check that can stop background tracking, each failed one with a button that fixes it. */
class DiagnosticsActivity : AppCompatActivity() {

    private class Fix(val label: String, @DrawableRes val icon: Int, val prominent: Boolean, val action: () -> Unit)

    private class Row(
        @DrawableRes val icon: Int,
        @ColorInt val color: Int,
        val title: String,
        val status: String,
        val desc: String,
        val fix: Fix? = null,
    )

    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var settings: Settings
    private var checkedAt = 0L

    /** The check being fixed, to confirm it once the user is back */
    private var fixing: Check? = null
    private var fixingPermission: String? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        edgeToEdge(binding.root)
        settings = Settings(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            refresh()
            binding.root.snack(getString(R.string.checked_just_now))
            true
        }
        binding.appSettings.setOnClickListener { openAppSettings() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        confirmFix()
    }

    private fun onPermissionResult() {
        val check = fixing ?: return
        val permission = fixingPermission
        refresh()
        if (Health.passes(this, check)) {
            confirmFix()
        } else if (permission != null && !shouldShowRequestPermissionRationale(permission)) {
            // Denied for good (or Android won't ask again): only app settings can change it now
            binding.root.snack(getString(R.string.still_denied, getString(check.title)))
            openAppSettings()
        } else {
            fixing = null
        }
    }

    /** Says "<check> fixed" when the check being fixed now passes. */
    private fun confirmFix() {
        val check = fixing ?: return
        if (!Health.passes(this, check)) return
        fixing = null
        binding.root.snack(getString(R.string.fixed, getString(check.title)))
    }

    private fun refresh() {
        checkedAt = System.currentTimeMillis()
        val root = binding.root
        val failing = Health.failing(this)

        // Summary
        if (failing.isEmpty()) {
            binding.summary.setCardBackgroundColor(getColor(R.color.ok_container))
            binding.summaryIcon.setImageResource(R.drawable.ic_verified_user)
            binding.summaryIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.ok))
            binding.summaryTitle.setText(R.string.all_good)
            binding.summaryTitle.setTextColor(getColor(R.color.on_ok_container))
            binding.summaryText.text = getString(R.string.all_good_text, ago(checkedAt))
            binding.summaryText.setTextColor(getColor(R.color.on_ok_container_variant))
        } else {
            val fg = root.themeColor(MaterialR.attr.colorOnErrorContainer)
            binding.summary.setCardBackgroundColor(root.themeColor(MaterialR.attr.colorErrorContainer))
            binding.summaryIcon.setImageResource(R.drawable.ic_error)
            binding.summaryIcon.imageTintList = ColorStateList.valueOf(root.themeColor(MaterialR.attr.colorError))
            binding.summaryTitle.text = Health.issuesTitle(this, failing.size)
            binding.summaryTitle.setTextColor(fg)
            binding.summaryText.text = getString(R.string.issues_text, ago(checkedAt))
            binding.summaryText.setTextColor(getColor(R.color.on_error_container_variant))
        }

        // Sections
        binding.sections.removeAllViews()
        section(R.string.permissions, Health.checks.filter { it != Check.BATTERY }.map(::checkRow))
        section(R.string.background, listOfNotNull(checkRow(Check.BATTERY), serviceRow(), oemRow()))
        section(R.string.sensors, listOf(playServicesRow()))
    }

    private fun section(title: Int, rows: List<Row>) {
        val header = layoutInflater.inflate(R.layout.section_header, binding.sections, false) as TextView
        header.setText(title)
        binding.sections.addView(header)
        rows.forEach { row ->
            ItemCheckBinding.inflate(layoutInflater, binding.sections, true).apply {
                icon.setImageResource(row.icon)
                icon.imageTintList = ColorStateList.valueOf(row.color)
                this.title.text = row.title
                status.text = row.status
                status.setTextColor(row.color)
                desc.text = row.desc
                fix.isVisible = row.fix != null
                row.fix?.let { f ->
                    val (bg, fg) = if (f.prominent) {
                        getColor(R.color.brand) to getColor(R.color.on_brand)
                    } else {
                        root.themeColor(MaterialR.attr.colorSecondaryContainer) to
                            root.themeColor(MaterialR.attr.colorOnSecondaryContainer)
                    }
                    fix.text = f.label
                    fix.setIconResource(f.icon)
                    fix.backgroundTintList = ColorStateList.valueOf(bg)
                    fix.setTextColor(fg)
                    fix.iconTint = ColorStateList.valueOf(fg)
                    fix.setOnClickListener { f.action() }
                }
            }
        }
    }

    private fun checkRow(check: Check): Row {
        val ok = Health.passes(this, check)
        val (okDesc, badDesc, fixLabel, fixIcon) = when (check) {
            Check.PRECISE -> CheckText(
                R.string.check_precise_ok,
                if (Health.coarseOnly(this)) R.string.check_precise_coarse else R.string.check_precise_bad,
                if (Health.coarseOnly(this)) R.string.fix_precise_coarse else R.string.allow,
                R.drawable.ic_location_on,
            )
            Check.BACKGROUND -> CheckText(R.string.check_background_ok, R.string.check_background_bad, R.string.fix_background, R.drawable.ic_share_location)
            Check.ACTIVITY -> CheckText(R.string.check_activity_ok, R.string.check_activity_bad, R.string.allow, R.drawable.ic_directions_walk)
            Check.NOTIFICATIONS -> CheckText(R.string.check_notifications_ok, R.string.check_notifications_bad, R.string.allow, R.drawable.ic_notifications)
            Check.BATTERY -> CheckText(R.string.check_battery_ok, R.string.check_battery_bad, R.string.fix_battery, R.drawable.ic_battery_saver)
        }
        return if (ok) {
            Row(R.drawable.ic_check_circle, getColor(R.color.ok), getString(check.title), getString(R.string.status_ok), getString(okDesc))
        } else {
            Row(
                R.drawable.ic_error, binding.root.themeColor(MaterialR.attr.colorError), getString(check.title),
                getString(R.string.status_action), getString(badDesc),
                Fix(getString(fixLabel), fixIcon, prominent = true) { fix(check) },
            )
        }
    }

    private fun serviceRow(): Row = if (TrackingService.running) {
        val high = TrackingService.highAccuracy ?: true
        Row(
            R.drawable.ic_check_circle, getColor(R.color.ok), getString(R.string.check_service),
            getString(R.string.status_running), getString(if (high) R.string.check_service_high else R.string.check_service_low),
        )
    } else {
        Row(
            R.drawable.ic_pause_circle, binding.root.themeColor(MaterialR.attr.colorOutline), getString(R.string.check_service),
            getString(R.string.status_stopped), getString(R.string.check_service_off),
            Fix(getString(R.string.start_tracking), R.drawable.ic_play_arrow, prominent = false, ::startTracking),
        )
    }

    private fun oemRow(): Row? {
        val (slug, name) = Health.oemVendor() ?: return null
        return if (settings.oemReviewed) {
            Row(
                R.drawable.ic_check_circle, getColor(R.color.ok), getString(R.string.check_oem),
                getString(R.string.status_reviewed), getString(R.string.check_oem_ok, name),
            )
        } else {
            Row(
                R.drawable.ic_warning, getColor(R.color.warn), getString(R.string.check_oem), getString(R.string.status_check),
                if (slug == "samsung") getString(R.string.check_oem_samsung) else getString(R.string.check_oem_generic, name),
                Fix(getString(R.string.open_guide), R.drawable.ic_open_in_new, prominent = false) {
                    settings.oemReviewed = true
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$slug")))
                },
            )
        }
    }

    private fun playServicesRow(): Row = if (Health.playServicesAvailable(this)) {
        Row(
            R.drawable.ic_check_circle, getColor(R.color.ok), getString(R.string.check_play),
            getString(R.string.status_available), getString(R.string.check_play_ok),
        )
    } else {
        Row(
            R.drawable.ic_error, binding.root.themeColor(MaterialR.attr.colorError), getString(R.string.check_play),
            getString(R.string.status_unavailable), getString(R.string.check_play_bad),
        )
    }

    private fun fix(check: Check) {
        fixing = check
        fixingPermission = null
        when (check) {
            Check.PRECISE -> ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            Check.BACKGROUND -> if (!Health.passes(this, Check.PRECISE)) {
                // Android only grants background location on top of foreground location
                fix(Check.PRECISE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.background_title)
                    .setMessage(R.string.background_message)
                    .setPositiveButton(R.string.continue_) { _, _ -> ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> fixing = null }
                    .show()
            }
            Check.ACTIVITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ask(Manifest.permission.ACTIVITY_RECOGNITION)
            Check.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ask(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Granted but turned off in system settings
                startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName),
                )
            }
            Check.BATTERY -> requestBatteryExemption()
        }
    }

    private fun ask(vararg permissions: String) {
        fixingPermission = permissions.first()
        permissionLauncher.launch(arrayOf(*permissions))
    }

    private fun startTracking() {
        if (!TrackingService.hasLocationPermission(this)) {
            binding.root.snack(getString(R.string.location_required))
            return
        }
        settings.trackingEnabled = true
        TrackingService.start(this)
        binding.root.snack(getString(R.string.tracking_started))
        binding.root.postDelayed(::refresh, 300)
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

    private fun openAppSettings() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private data class CheckText(val okDesc: Int, val badDesc: Int, val fixLabel: Int, @DrawableRes val fixIcon: Int)
}
