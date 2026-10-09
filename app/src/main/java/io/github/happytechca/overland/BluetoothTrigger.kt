package io.github.happytechca.overland

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Tells [TrackingService] when one of the devices chosen for the Bluetooth trigger (e.g. the car) is connected.
 * Listens to connection broadcasts, so it costs nothing while waiting. Main thread only.
 */
class BluetoothTrigger(
    private val context: Context,
    private val settings: Settings,
    private val onChange: () -> Unit,
) {
    /** Chosen devices connected right now, by address */
    private val connected = linkedMapOf<String, String>()

    val active get() = connected.isNotEmpty()

    /** Name of a connected chosen device, for the main screen */
    val deviceName: String? get() = connected.values.firstOrNull()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val chosen = chosen()[device.address] ?: return
            val changed = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> connected.put(device.address, chosen) == null
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> connected.remove(device.address) != null
                else -> false
            }
            if (changed) {
                Log.i(TAG, "$chosen ${if (device.address in connected) "connected" else "disconnected"}")
                onChange()
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Exported: these are sent by the Bluetooth stack, not the system; only Android can send them (protected)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        connected.clear()
    }

    /**
     * Re-reads the settings and looks for chosen devices that are already connected (tracking started or the
     * trigger was set up while in the car), through the hands-free and audio profiles cars use.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun refresh() {
        val chosen = chosen()
        val before = connected.keys.toSet()
        connected.keys.retainAll(chosen.keys)
        if (connected.keys != before) onChange()
        if (chosen.isEmpty() || !hasPermission(context)) return

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        var changed = false
                        try {
                            proxy.connectedDevices.forEach { device ->
                                val name = chosen()[device.address] ?: return@forEach
                                if (connected.put(device.address, name) == null) changed = true
                            }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "No Bluetooth permission", e)
                        }
                        adapter.closeProfileProxy(p, proxy)
                        if (changed) onChange()
                    }

                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            } catch (e: SecurityException) {
                Log.w(TAG, "No Bluetooth permission", e)
            }
        }
    }

    /** Address → name of the chosen devices; empty when the trigger is off */
    private fun chosen(): Map<String, String> =
        if (settings.btTrigger) settings.btDevices.associate { it.address to it.name } else emptyMap()

    companion object {
        private const val TAG = "BluetoothTrigger"

        /** Android 12+ needs "Nearby devices" to see connections and paired devices; older versions grant it at install. */
        fun hasPermission(context: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
