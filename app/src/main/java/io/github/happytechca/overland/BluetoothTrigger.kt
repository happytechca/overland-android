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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Tells [TrackingService] when one of the devices chosen for the Bluetooth trigger (e.g. the car) is connected.
 * Listens to connection broadcasts, so it costs nothing while waiting. A device still counts as connected for
 * [GRACE_MS] after it disconnects, so the parking spot is recorded accurately and a brief drop mid-drive
 * doesn't switch to low power. Main thread only.
 */
class BluetoothTrigger(
    private val context: Context,
    private val settings: Settings,
    private val onChange: () -> Unit,
) {
    /** Chosen devices connected right now or within [GRACE_MS], by address */
    private val connected = linkedMapOf<String, String>()
    /** Pending removals of disconnected devices, by address */
    private val disconnecting = mutableMapOf<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    val active get() = connected.isNotEmpty()

    /** Name of a connected chosen device, for the main screen */
    val deviceName: String? get() = connected.values.firstOrNull()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val address = device.address
            val chosen = chosen()[address] ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    disconnecting.remove(address)?.let(handler::removeCallbacks)
                    if (connected.put(address, chosen) == null) {
                        Log.i(TAG, "$chosen connected")
                        onChange()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (address !in connected || address in disconnecting) return
                    Log.i(TAG, "$chosen disconnected, still active for ${GRACE_MS / 1000} s")
                    val remove = Runnable {
                        disconnecting.remove(address)
                        if (connected.remove(address) != null) onChange()
                    }
                    disconnecting[address] = remove
                    handler.postDelayed(remove, GRACE_MS)
                }
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
        disconnecting.values.forEach(handler::removeCallbacks)
        disconnecting.clear()
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
        disconnecting.keys.filter { it !in chosen }.forEach { handler.removeCallbacks(disconnecting.remove(it)!!) }
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
        private const val GRACE_MS = 2 * 60_000L

        /** Android 12+ needs "Nearby devices" to see connections and paired devices; older versions grant it at install. */
        fun hasPermission(context: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
