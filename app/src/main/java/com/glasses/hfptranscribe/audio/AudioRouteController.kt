package com.glasses.hfptranscribe.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class DeviceScan(
    val candidates: List<AudioInputCandidate>,
    val bluetoothPermissionMissing: Boolean,
)

/**
 * Routes capture onto a Bluetooth SCO headset with
 * [AudioManager.setCommunicationDevice], then returns the phone to
 * [AudioManager.MODE_NORMAL] when recording stops.
 */
class AudioRouteController(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val devices = LinkedHashMap<Int, AudioDeviceInfo>()
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var listener: AudioManager.OnCommunicationDeviceChangedListener? = null

    fun scan(): DeviceScan {
        var permissionMissing = false
        val inputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        } catch (_: SecurityException) {
            permissionMissing = true
            emptyList()
        }
        val communication = try {
            audioManager.availableCommunicationDevices.toList()
        } catch (_: SecurityException) {
            permissionMissing = true
            emptyList()
        }
        devices.clear()
        (inputs + communication).forEach { device -> devices[device.id] = device }
        val candidates = devices.values.mapNotNull { device ->
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputKind.BLUETOOTH_SCO
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputKind.BUILTIN_MIC
                else -> null
            } ?: return@mapNotNull null
            val name = try {
                device.productName?.toString().orEmpty()
            } catch (_: SecurityException) {
                permissionMissing = true
                ""
            }
            AudioInputCandidate(id = device.id, kind = kind, productName = name)
        }
        return DeviceScan(candidates, permissionMissing)
    }

    fun device(id: Int): AudioDeviceInfo? = devices[id]

    fun applyGlasses(device: AudioDeviceInfo): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return try {
            audioManager.setCommunicationDevice(device)
        } catch (_: SecurityException) {
            false
        }
    }

    fun applyPhoneMic() {
        clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun communicationDeviceId(): Int? = audioManager.communicationDevice?.id

    fun watch(onChanged: (Int?) -> Unit) {
        unwatch()
        val created = AudioManager.OnCommunicationDeviceChangedListener { device ->
            onChanged(device?.id)
        }
        audioManager.addOnCommunicationDeviceChangedListener(callbackExecutor, created)
        listener = created
    }

    fun release() {
        unwatch()
        clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun unwatch() {
        listener?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
        listener = null
    }

    private fun clearCommunicationDevice() {
        try {
            audioManager.clearCommunicationDevice()
        } catch (_: Throwable) {
            // Already cleared, or the route was never claimed.
        }
    }
}
