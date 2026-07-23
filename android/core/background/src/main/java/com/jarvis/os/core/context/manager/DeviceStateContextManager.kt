package com.jarvis.os.core.context.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.jarvis.os.core.context.model.AudioSnapshot
import com.jarvis.os.core.context.model.BatterySnapshot
import com.jarvis.os.core.context.model.NetworkType
import com.jarvis.os.core.context.model.VolumeProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface DeviceStateContextManager {
    val batteryState: StateFlow<BatterySnapshot>
    val audioState: StateFlow<AudioSnapshot>
    val networkState: StateFlow<NetworkType>
    val volumeState: StateFlow<VolumeProfile>
    fun startMonitoring()
    fun stopMonitoring()
}

/**
 * Device State Context Manager monitoring Battery, Charging, Audio Peripherals,
 * Network Connectivity, and Volume Profiles via event-driven system callbacks.
 */
class DeviceStateContextManagerImpl(
    private val context: Context
) : DeviceStateContextManager {

    companion object {
        private const val TAG = "JarvisDeviceStateMgr"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _batteryState = MutableStateFlow(BatterySnapshot())
    override val batteryState: StateFlow<BatterySnapshot> = _batteryState.asStateFlow()

    private val _audioState = MutableStateFlow(AudioSnapshot())
    override val audioState: StateFlow<AudioSnapshot> = _audioState.asStateFlow()

    private val _networkState = MutableStateFlow(NetworkType.OFFLINE)
    override val networkState: StateFlow<NetworkType> = _networkState.asStateFlow()

    private val _volumeState = MutableStateFlow(VolumeProfile.NORMAL)
    override val volumeState: StateFlow<VolumeProfile> = _volumeState.asStateFlow()

    private var isMonitoring = false

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                when (action) {
                    Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                        updateBatteryState(intent)
                    }
                    Intent.ACTION_HEADSET_PLUG, AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        updateAudioState()
                    }
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        updateVolumeState()
                    }
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkState()
        }

        override fun onLost(network: Network) {
            updateNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetworkState()
        }
    }

    override fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        Log.i(TAG, "Starting Device State Context monitoring...")

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }

        try {
            context.registerReceiver(systemReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register systemReceiver", e)
        }

        // Register NetworkCallback
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register networkCallback", e)
        }

        // Initial updates
        updateBatteryState(null)
        updateAudioState()
        updateNetworkState()
        updateVolumeState()
    }

    override fun stopMonitoring() {
        if (!isMonitoring) return
        try {
            context.unregisterReceiver(systemReceiver)
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering device state callbacks", e)
        }
        isMonitoring = false
        Log.i(TAG, "Stopped Device State Context monitoring.")
    }

    private fun updateBatteryState(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 100

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val isPowerSave = powerManager?.isPowerSaveMode ?: false

        _batteryState.value = BatterySnapshot(
            levelPercentage = pct,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSave
        )
        Log.d(TAG, "Updated BatterySnapshot: level=$pct%, charging=$isCharging, powerSave=$isPowerSave")
    }

    private fun updateAudioState() {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
        var hasHeadphones = false
        var hasCarBluetooth = false
        val names = mutableListOf<String>()

        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    hasHeadphones = true
                    device.productName?.toString()?.let { names.add(it) }
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    hasCarBluetooth = true
                    device.productName?.toString()?.let { names.add(it) }
                }
            }
        }

        _audioState.value = AudioSnapshot(
            isHeadphonesConnected = hasHeadphones,
            isCarBluetoothConnected = hasCarBluetooth,
            connectedDeviceNames = names
        )
        Log.d(TAG, "Updated AudioSnapshot: headphones=$hasHeadphones, carBT=$hasCarBluetooth, devices=$names")
    }

    private fun updateNetworkState() {
        val capabilities = connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)
        val netType = when {
            capabilities == null -> NetworkType.OFFLINE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.OFFLINE
        }
        _networkState.value = netType
        Log.d(TAG, "Updated NetworkType: $netType")
    }

    private fun updateVolumeState() {
        val profile = when (audioManager?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> VolumeProfile.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> VolumeProfile.VIBRATE
            else -> VolumeProfile.NORMAL
        }
        _volumeState.value = profile
        Log.d(TAG, "Updated VolumeProfile: $profile")
    }
}
