package io.github.happytechca.overland

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.github.happytechca.overland.databinding.ActivitySettingsBinding
import io.github.happytechca.overland.databinding.ItemChoiceBinding

/** Settings are saved as soon as they change; the running service picks them up. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings
    private var testing = false
    private var profileRows: Map<String, ItemChoiceBinding> = emptyMap()

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
        private val INTERVALS = listOf(15 to "15 s", 30 to "30 s", 60 to "1 min", 300 to "5 min", 900 to "15 min")
        private val BATCHES = listOf(50, 100, 200, 500)
        private val PROFILES = listOf(
            Triple(Settings.PROFILE_AUTO, R.string.profile_auto, R.string.profile_auto_help),
            Triple(Settings.PROFILE_HIGH, R.string.profile_high, R.string.profile_high_help),
            Triple(Settings.PROFILE_LOW, R.string.profile_low, R.string.profile_low_help),
        )
    }
}
