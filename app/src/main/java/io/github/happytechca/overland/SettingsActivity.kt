package io.github.happytechca.overland

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.happytechca.overland.databinding.ActivitySettingsBinding
import io.github.happytechca.overland.databinding.DialogZoneBinding
import io.github.happytechca.overland.databinding.ItemChoiceBinding
import io.github.happytechca.overland.databinding.ItemZoneBinding
import java.util.Locale

/** Settings are saved as soon as they change; the running service picks them up. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings
    private var testing = false
    private var profileRows: Map<String, ItemChoiceBinding> = emptyMap()
    private var locating = false
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            enableBluetoothTrigger()
        } else {
            binding.btSwitch.isChecked = false
            binding.root.snack(getString(R.string.bt_permission_needed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        edgeToEdge(binding.root)
        settings = Settings(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Server
        bindText(binding.url, settings.url) { settings.url = it }
        bindText(binding.token, settings.token) { settings.token = it }
        bindText(binding.deviceId, settings.deviceId) { settings.deviceId = it }
        binding.test.setOnClickListener { testConnection() }

        // Uploads
        bindChoices(binding.intervals, INTERVALS, settings.uploadIntervalSec) { settings.uploadIntervalSec = it }
        bindChoices(binding.batches, BATCHES.map { it to it.toString() }, settings.batchSize) { settings.batchSize = it }

        // Tracking
        val rows = PROFILES.map { (id, title, help) ->
            ItemChoiceBinding.inflate(layoutInflater, binding.profiles, true).apply {
                this.title.setText(title)
                subtitle.setText(help)
                root.setOnClickListener {
                    if (settings.accuracyProfile == id) return@setOnClickListener
                    settings.accuracyProfile = id
                    showProfile()
                    saved()
                }
            }
        }
        profileRows = PROFILES.map { it.first }.zip(rows).toMap()
        showProfile()

        binding.bootSwitch.isChecked = settings.startOnBoot
        binding.bootSwitch.setOnCheckedChangeListener { _, on -> settings.startOnBoot = on; saved() }
        binding.bootRow.setOnClickListener { binding.bootSwitch.toggle() }
        binding.tripSwitch.isChecked = settings.tripNotification
        binding.tripSwitch.setOnCheckedChangeListener { _, on -> settings.tripNotification = on; saved() }
        binding.tripRow.setOnClickListener { binding.tripSwitch.toggle() }

        // Bluetooth trigger
        binding.btSwitch.isChecked = settings.btTrigger
        binding.btSwitch.setOnCheckedChangeListener { _, on ->
            when {
                !on -> {
                    if (settings.btTrigger) saved()
                    settings.btTrigger = false
                    showBluetooth()
                }
                BluetoothTrigger.hasPermission(this) -> enableBluetoothTrigger()
                else -> btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        binding.btRow.setOnClickListener { binding.btSwitch.toggle() }
        binding.btDevicesRow.setOnClickListener { chooseDevices() }
        showBluetooth()

        // Quiet zones
        binding.addZone.setOnClickListener { addZone() }
        showZones()

        // Diagnostics, About
        binding.diagnostics.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        binding.version.text = packageManager.getPackageInfo(packageName, 0).versionName
        binding.source.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_url))))
        }
    }

    override fun onResume() {
        super.onResume()
        val failing = Health.failing(this)
        binding.diagnosticsSummary.text =
            if (failing.isEmpty()) getString(R.string.all_checks_passed) else Health.issuesTitle(this, failing.size)
        binding.diagnosticsSummary.setTextColor(
            binding.root.themeColor(if (failing.isEmpty()) MaterialR.attr.colorOnSurfaceVariant else MaterialR.attr.colorError),
        )
    }

    private fun showProfile() {
        profileRows.forEach { (id, row) -> row.radio.isChecked = id == settings.accuracyProfile }
    }

    private fun saved() = binding.root.snack(getString(R.string.saved))

    private fun enableBluetoothTrigger() {
        settings.btTrigger = true
        showBluetooth()
        if (settings.btDevices.isEmpty()) chooseDevices() else saved()
    }

    private fun showBluetooth() {
        binding.btDevicesRow.isVisible = settings.btTrigger
        val devices = settings.btDevices
        binding.btDevices.text =
            if (devices.isEmpty()) getString(R.string.bt_devices_none) else devices.joinToString(", ") { it.name }
    }

    /** Picks the trigger devices among the paired ones (plus any chosen earlier and since unpaired, to remove them). */
    @SuppressLint("MissingPermission") // checked by BluetoothTrigger.hasPermission()
    private fun chooseDevices() {
        if (!BluetoothTrigger.hasPermission(this)) {
            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val bonded = try {
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { BtDevice(it.address, it.name ?: it.address) }
        } catch (e: SecurityException) {
            emptyList()
        }
        val chosen = settings.btDevices
        val devices = (bonded + chosen).distinctBy { it.address }.sortedBy { it.name.lowercase() }
        if (devices.isEmpty()) {
            binding.root.snack(getString(R.string.bt_no_paired))
            return
        }
        val checked = devices.map { d -> chosen.any { it.address == d.address } }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bt_choose)
            .setMultiChoiceItems(devices.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.save) { _, _ ->
                settings.btDevices = devices.filterIndexed { i, _ -> checked[i] }
                showBluetooth()
                saved()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showZones() {
        binding.zones.removeAllViews()
        settings.zones.forEachIndexed { index, zone ->
            ItemZoneBinding.inflate(layoutInflater, binding.zones, true).apply {
                title.text = zone.name
                subtitle.text = getString(
                    R.string.zone_subtitle, zone.radiusM,
                    String.format(Locale.US, "%.5f, %.5f", zone.latitude, zone.longitude),
                )
                root.setOnClickListener { editZone(index) }
            }
        }
    }

    /** New zone centred on a fresh fix */
    @SuppressLint("MissingPermission") // checked by hasLocationPermission()
    private fun addZone() {
        if (locating) return
        if (!TrackingService.hasLocationPermission(this)) {
            binding.root.snack(getString(R.string.location_required))
            return
        }
        locating = true
        binding.addZone.isEnabled = false
        binding.root.snack(getString(R.string.zone_locating))
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnCompleteListener { task ->
                locating = false
                binding.addZone.isEnabled = true
                val location = task.result.takeIf { task.isSuccessful }
                if (location == null) {
                    binding.root.snack(getString(R.string.zone_no_location))
                    return@addOnCompleteListener
                }
                val name = if (settings.zones.isEmpty()) getString(R.string.zone_default_name) else ""
                zoneDialog(Zone(name, location.latitude, location.longitude, 100), isNew = true) { zone ->
                    settings.zones = settings.zones + zone
                    showZones()
                    saved()
                }
            }
    }

    private fun editZone(index: Int) {
        val zone = settings.zones.getOrNull(index) ?: return
        zoneDialog(zone, isNew = false, onDelete = {
            settings.zones = settings.zones.filterIndexed { i, _ -> i != index }
            showZones()
            binding.root.snack(getString(R.string.zone_deleted))
        }) { edited ->
            settings.zones = settings.zones.mapIndexed { i, z -> if (i == index) edited else z }
            showZones()
            saved()
        }
    }

    private fun zoneDialog(zone: Zone, isNew: Boolean, onDelete: (() -> Unit)? = null, onSave: (Zone) -> Unit) {
        val view = DialogZoneBinding.inflate(layoutInflater)
        view.name.setText(zone.name)
        view.radiusHelp.isVisible = isNew
        var radius = zone.radiusM
        (RADII + radius).distinct().sorted().forEach { r ->
            val chip = layoutInflater.inflate(R.layout.item_chip, view.radii, false) as Chip
            chip.id = View.generateViewId()
            chip.text = getString(R.string.meters, r)
            chip.isChecked = r == radius
            chip.setOnClickListener { radius = r }
            view.radii.addView(chip)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isNew) R.string.zone_new else R.string.zone_edit)
            .setView(view.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = view.name.text.toString().trim().ifEmpty { getString(R.string.zone_default_name) }
                onSave(zone.copy(name = name, radiusM = radius))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (onDelete != null) setNeutralButton(R.string.delete) { _, _ -> onDelete() } }
            .show()
    }

    /** Saves on every change; says "Saved" when leaving a field that changed. */
    private fun bindText(field: EditText, value: String, save: (String) -> Unit) {
        field.setText(value)
        var atFocus = value
        field.doAfterTextChanged { save(it.toString().trim()) }
        field.setOnFocusChangeListener { _, hasFocus ->
            val now = field.text.toString().trim()
            if (hasFocus) atFocus = now else if (now != atFocus) saved()
        }
    }

    private fun bindChoices(group: ChipGroup, choices: List<Pair<Int, String>>, current: Int, save: (Int) -> Unit) {
        choices.forEach { (value, label) ->
            val chip = layoutInflater.inflate(R.layout.item_chip, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            chip.tag = value
            chip.isChecked = value == current
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            val value = ids.firstOrNull()?.let { g.findViewById<Chip>(it).tag as Int } ?: return@setOnCheckedStateChangeListener
            save(value)
            saved()
        }
    }

    private fun testConnection() {
        if (testing) return
        testing = true
        binding.test.setText(R.string.testing)
        binding.testResult.isVisible = false
        Thread {
            val result = Uploader.uploadAll(applicationContext)
            runOnUiThread {
                testing = false
                binding.test.setText(R.string.test_connection)
                val ok = settings.lastUploadOk
                val color = if (ok) getColor(R.color.ok) else binding.root.themeColor(MaterialR.attr.colorError)
                binding.testResult.text = result
                binding.testResult.setTextColor(color)
                binding.testResult.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    if (ok) R.drawable.ic_check_circle else R.drawable.ic_error, 0, 0, 0,
                )
                TextViewCompat.setCompoundDrawableTintList(binding.testResult, ColorStateList.valueOf(color))
                binding.testResult.isVisible = true
            }
        }.start()
    }

    companion object {
        private val INTERVALS = listOf(30 to "30 s", 60 to "1 min", 300 to "5 min", 900 to "15 min")
        private val BATCHES = listOf(50, 100, 200, 500)
        private val RADII = listOf(50, 100, 125, 150, 200, 300)
        private val PROFILES = listOf(
            Triple(Settings.PROFILE_AUTO, R.string.profile_auto, R.string.profile_auto_help),
            Triple(Settings.PROFILE_HIGH, R.string.profile_high, R.string.profile_high_help),
            Triple(Settings.PROFILE_LOW, R.string.profile_low, R.string.profile_low_help),
        )
    }
}
