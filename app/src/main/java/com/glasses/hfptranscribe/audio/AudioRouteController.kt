package com.glasses.hfptranscribe.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class DeviceScan(
    val candidates: List<AudioInputCandidate>,
    val bluetoothPermissionMissing: Boolean,
)

class RouteException(
    val summary: String,
    val diagnostics: String,
) : IllegalStateException(summary)

/**
 * Opens the glasses microphone.
 *
 * [input] is the SCO source for [android.media.AudioRecord.setPreferredDevice].
 * [sinkId] is the communication-device sink used with setCommunicationDevice.
 * [legacySco] means the route was opened with startBluetoothSco instead.
 */
data class OpenedGlasses(
    val input: AudioDeviceInfo?,
    val sinkId: Int?,
    val legacySco: Boolean,
    val productName: String,
)

/**
 * Routes capture onto a Bluetooth SCO headset, then returns the phone to
 * [AudioManager.MODE_NORMAL] when recording stops.
 *
 * setCommunicationDevice is only ever given a device from
 * [AudioManager.getAvailableCommunicationDevices]. The input port is kept
 * separate for the recorder. If the modern request is rejected, recording
 * falls back to [AudioManager.startBluetoothSco].
 */
class AudioRouteController(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val inputs = LinkedHashMap<Int, AudioDeviceInfo>()
    private val communication = LinkedHashMap<Int, AudioDeviceInfo>()
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var listener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var legacyScoStarted = false

    fun scan(): DeviceScan {
        inputs.clear()
        communication.clear()
        var permissionMissing = false
        try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { device ->
                inputs[device.id] = device
            }
        } catch (_: SecurityException) {
            permissionMissing = true
        }
        try {
            audioManager.availableCommunicationDevices.forEach { device ->
                communication[device.id] = device
            }
        } catch (_: SecurityException) {
            permissionMissing = true
        }
        val candidates = ArrayList<AudioInputCandidate>()
        fun add(device: AudioDeviceInfo) {
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputKind.BLUETOOTH_SCO
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputKind.BUILTIN_MIC
                else -> null
            } ?: return
            val name = try {
                device.productName?.toString().orEmpty()
            } catch (_: SecurityException) {
                permissionMissing = true
                ""
            }
            candidates += AudioInputCandidate(id = device.id, kind = kind, productName = name)
        }
        inputs.values.forEach { add(it) }
        communication.values.forEach { device ->
            if (device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return@forEach
            if (inputs.containsKey(device.id)) return@forEach
            add(device)
        }
        return DeviceScan(candidates, permissionMissing)
    }

    fun device(id: Int): AudioDeviceInfo? = inputs[id] ?: communication[id]

    suspend fun openGlasses(candidateId: Int): OpenedGlasses {
        unwatch()
        stopLegacySco()
        clearCommunicationDevice()
        val modeBefore = audioManager.mode
        val selected = endpoint(candidateId)
            ?: throw failure(
                summary = "The glasses disappeared before recording could start.",
                step = "selected device id=$candidateId was not in the latest scan",
                modeBefore = modeBefore,
            )
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val attempts = mutableListOf<String>()
        repeat(SET_ATTEMPTS) { attempt ->
            val target = matchCommunicationSink(selected)
            if (target == null) {
                attempts += "try ${attempt + 1}: no TYPE_BLUETOOTH_SCO device in availableCommunicationDevices"
            } else {
                when (val result = trySetCommunicationDevice(target)) {
                    is SetResult.Accepted -> {
                        if (awaitSink(target.id, SETTLE_MS) || scoSinkActive(target)) {
                            return opened(selected, target)
                        }
                        val active = audioManager.communicationDevice?.id
                        attempts += "try ${attempt + 1}: setCommunicationDevice accepted sink id=${target.id} but active device was ${active ?: "none"}"
                        clearCommunicationDevice()
                    }
                    is SetResult.Rejected -> attempts += "try ${attempt + 1}: ${result.detail}"
                }
            }
            delay(SET_DELAY_MS)
        }
        clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val legacyDetail = startLegacySco()
        if (legacyDetail == null) {
            val input = matchInput(selected)
            Log.i(
                ROUTE_LOG,
                "opened glasses via startBluetoothSco input=${input?.id} name=${displayName(selected, input)}",
            )
            return OpenedGlasses(
                input = input?.takeIf { it.isSource },
                sinkId = null,
                legacySco = true,
                productName = displayName(selected, input),
            )
        }
        attempts += legacyDetail
        throw failure(
            summary = "Could not open the glasses microphone. ${shortReason(attempts)}",
            step = attempts.joinToString("; "),
            modeBefore = modeBefore,
        )
    }

    fun applyPhoneMic() {
        stopLegacySco()
        clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun communicationDeviceId(): Int? = audioManager.communicationDevice?.id

    fun watchRoute(sinkId: Int?, legacy: Boolean, onLost: () -> Unit) {
        unwatch()
        if (legacy) {
            watchLegacyDisconnect(onLost)
            return
        }
        if (sinkId == null) return
        val created = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device?.id != sinkId) onLost()
        }
        audioManager.addOnCommunicationDeviceChangedListener(callbackExecutor, created)
        listener = created
    }

    fun release() {
        unwatch()
        stopLegacySco()
        clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun opened(selected: ScoEndpoint, sink: AudioDeviceInfo): OpenedGlasses {
        val input = matchInput(selected)
        Log.i(
            ROUTE_LOG,
            "opened glasses via setCommunicationDevice sink=${sink.id} input=${input?.id} name=${displayName(selected, input)}",
        )
        return OpenedGlasses(
            input = input?.takeIf { it.isSource },
            sinkId = sink.id,
            legacySco = false,
            productName = displayName(selected, input),
        )
    }

    private fun matchCommunicationSink(selected: ScoEndpoint): AudioDeviceInfo? {
        val peers = communication.values
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            .map { it.toEndpoint() }
        val matched = ScoSinkMatcher.match(selected, peers) ?: return null
        return communication[matched.id]
    }

    private fun matchInput(selected: ScoEndpoint): AudioDeviceInfo? {
        refreshPorts()
        val peers = inputs.values
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            .map { it.toEndpoint() }
        val matched = ScoSinkMatcher.match(selected, peers) ?: return inputs[selected.id]?.takeIf { it.isSource }
        return inputs[matched.id]
    }

    private fun refreshPorts() {
        try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { device ->
                inputs[device.id] = device
            }
        } catch (_: SecurityException) {
            // Leave the previous scan in place.
        }
        try {
            audioManager.availableCommunicationDevices.forEach { device ->
                communication[device.id] = device
            }
        } catch (_: SecurityException) {
            // Leave the previous scan in place.
        }
    }

    private fun trySetCommunicationDevice(sink: AudioDeviceInfo): SetResult {
        return try {
            if (audioManager.setCommunicationDevice(sink)) {
                SetResult.Accepted
            } else {
                SetResult.Rejected("setCommunicationDevice returned false for sink id=${sink.id}")
            }
        } catch (error: IllegalArgumentException) {
            SetResult.Rejected(
                "setCommunicationDevice threw IllegalArgumentException for sink id=${sink.id}: ${error.message}",
            )
        } catch (error: SecurityException) {
            SetResult.Rejected("setCommunicationDevice threw SecurityException: ${error.message}")
        }
    }

    private fun scoSinkActive(target: AudioDeviceInfo): Boolean {
        val active = audioManager.communicationDevice ?: return false
        if (active.id == target.id) return true
        if (active.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return false
        return ScoSinkMatcher.match(target.toEndpoint(), listOf(active.toEndpoint())) != null
    }

    private suspend fun awaitSink(id: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (audioManager.communicationDevice?.id == id) return true
            delay(100)
        }
        return audioManager.communicationDevice?.id == id
    }

    /**
     * @return null when SCO reached [AudioManager.SCO_AUDIO_STATE_CONNECTED],
     * otherwise a description of why it did not.
     */
    @Suppress("DEPRECATION")
    private suspend fun startLegacySco(): String? {
        val connected = AtomicBoolean(false)
        var accept = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!accept) return
                if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.set(true)
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        try {
            register(receiver, filter)
            accept = true
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            legacyScoStarted = true
        } catch (error: SecurityException) {
            unregister(receiver)
            legacyScoStarted = false
            return "startBluetoothSco threw SecurityException: ${error.message}"
        } catch (error: Throwable) {
            unregister(receiver)
            legacyScoStarted = false
            return "startBluetoothSco failed: ${error.message}"
        }
        val deadline = SystemClock.elapsedRealtime() + SCO_TIMEOUT_MS
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (connected.get()) return null
                delay(100)
            }
        } finally {
            unregister(receiver)
        }
        return "ACTION_SCO_AUDIO_STATE_UPDATED did not report SCO_AUDIO_STATE_CONNECTED within ${SCO_TIMEOUT_MS}ms"
    }

    @Suppress("DEPRECATION")
    private fun watchLegacyDisconnect(onDropped: () -> Unit) {
        var accept = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!accept) return
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED ||
                    state == AudioManager.SCO_AUDIO_STATE_ERROR
                ) {
                    onDropped()
                }
            }
        }
        scoReceiver = receiver
        register(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        accept = true
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun unregister(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    @Suppress("DEPRECATION")
    private fun scoFlag(): Boolean = audioManager.isBluetoothScoOn

    @Suppress("DEPRECATION")
    private fun stopLegacySco() {
        if (!legacyScoStarted) return
        legacyScoStarted = false
        try {
            audioManager.stopBluetoothSco()
        } catch (_: Throwable) {
            // SCO was already stopped.
        }
        try {
            audioManager.isBluetoothScoOn = false
        } catch (_: Throwable) {
            // The flag was already cleared.
        }
    }

    private fun unwatch() {
        listener?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
        listener = null
        scoReceiver?.let { unregister(it) }
        scoReceiver = null
    }

    private fun clearCommunicationDevice() {
        try {
            audioManager.clearCommunicationDevice()
        } catch (_: Throwable) {
            // Already cleared, or the route was never claimed.
        }
    }

    private fun endpoint(id: Int): ScoEndpoint? {
        return (inputs[id] ?: communication[id])?.toEndpoint()
    }

    private fun failure(summary: String, step: String, modeBefore: Int): RouteException {
        val diagnostics = diagnostics(step, modeBefore)
        Log.i(ROUTE_LOG, diagnostics)
        return RouteException(summary, diagnostics)
    }

    private fun diagnostics(step: String, modeBefore: Int): String {
        val recordings = otherRecordings()
        val note = when {
            modeBefore == AudioManager.MODE_IN_CALL ->
                "Mode was MODE_IN_CALL before this app tried the route, so a phone call owns audio."
            modeBefore == AudioManager.MODE_IN_COMMUNICATION ->
                "Mode was already MODE_IN_COMMUNICATION, so another app (often Meta AI or a call) may own the route."
            recordings.isNotEmpty() ->
                "Another app is recording and may hold the microphone."
            else -> null
        }
        val active = audioManager.communicationDevice
        val activeText = if (active == null) {
            "none"
        } else {
            "id=${active.id} ${audioTypeName(active.type)} name=${active.productName ?: "-"}"
        }
        return formatRouteDiagnostics(
            step = step,
            modeBefore = modeLabel(modeBefore),
            modeNow = modeLabel(audioManager.mode),
            scoOn = scoFlag(),
            activeCommunicationDevice = activeText,
            devices = listed("input", inputs.values) + listed("communication", communication.values),
            otherRecordings = recordings,
            routeNote = note,
        )
    }

    private fun otherRecordings(): List<OtherRecording> {
        val ours = context.packageName
        return try {
            audioManager.activeRecordingConfigurations.mapNotNull { config ->
                val pkg = clientPackage(config)
                if (pkg == ours) return@mapNotNull null
                val device = config.audioDevice
                val deviceText = if (device == null) {
                    "device=none"
                } else {
                    "device=${audioTypeName(device.type)} id=${device.id}"
                }
                OtherRecording(pkg, "${audioSourceName(config.clientAudioSource)} $deviceText")
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun clientPackage(config: AudioRecordingConfiguration): String {
        return try {
            val method = config.javaClass.getMethod("getClientPackageName")
            (method.invoke(config) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } ?: "(hidden)"
    }

    private fun listed(group: String, devices: Collection<AudioDeviceInfo>): List<ListedDevice> {
        return devices.map { device ->
            ListedDevice(
                group = group,
                id = device.id,
                typeName = audioTypeName(device.type),
                source = device.isSource,
                sink = device.isSink,
                address = runCatching { device.address }.getOrDefault("").orEmpty(),
                productName = runCatching { device.productName?.toString() }.getOrDefault("").orEmpty(),
            )
        }
    }

    private fun displayName(selected: ScoEndpoint, input: AudioDeviceInfo?): String {
        return selected.productName.ifBlank {
            input?.productName?.toString().orEmpty()
        }
    }

    private fun AudioDeviceInfo.toEndpoint(): ScoEndpoint {
        return ScoEndpoint(
            id = id,
            address = runCatching { address }.getOrDefault("").orEmpty(),
            productName = runCatching { productName?.toString() }.getOrDefault("").orEmpty(),
        )
    }

    private sealed interface SetResult {
        data object Accepted : SetResult
        data class Rejected(val detail: String) : SetResult
    }

    private companion object {
        const val SET_ATTEMPTS = 4
        const val SET_DELAY_MS = 250L
        const val SETTLE_MS = 800L
        const val SCO_TIMEOUT_MS = 5_000L
    }
}

internal const val ROUTE_LOG = "GlassesRoute"

internal fun modeLabel(mode: Int): String {
    val name = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
        else -> "MODE_$mode"
    }
    return "$name ($mode)"
}

internal fun audioTypeName(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        else -> "TYPE_$type"
    }
}

internal fun audioSourceName(source: Int): String {
    return when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        else -> "SOURCE_$source"
    }
}

private fun shortReason(attempts: List<String>): String {
    val modern = attempts.any {
        it.contains("setCommunicationDevice") || it.contains("availableCommunicationDevices")
    }
    // Match the legacy step only. "TYPE_BLUETOOTH_SCO" also contains "SCO" and is a modern-path line.
    val legacy = attempts.any {
        it.contains("startBluetoothSco") || it.contains("SCO_AUDIO_STATE")
    }
    return when {
        modern && legacy ->
            "setCommunicationDevice rejected the Bluetooth headset, and the legacy SCO connection did not come up."
        legacy -> "The legacy Bluetooth SCO connection did not come up."
        else -> "setCommunicationDevice rejected the Bluetooth headset."
    }
}
