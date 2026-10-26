package com.glasses.hfptranscribe

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.glasses.hfptranscribe.audio.AudioRouteController
import com.glasses.hfptranscribe.audio.AudioRouteSelector
import com.glasses.hfptranscribe.audio.CaptureSource
import com.glasses.hfptranscribe.audio.DeviceScan
import com.glasses.hfptranscribe.audio.pcm16Level
import com.glasses.hfptranscribe.audio.pcm16ToFloat
import com.glasses.hfptranscribe.session.OpenSession
import com.glasses.hfptranscribe.session.SessionStore
import com.glasses.hfptranscribe.stt.AsrModel
import com.glasses.hfptranscribe.stt.ModelRepository
import com.glasses.hfptranscribe.stt.SherpaStreamingTranscriber
import com.glasses.hfptranscribe.stt.SpeechTranscriber
import com.glasses.hfptranscribe.transcript.TranscriptLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.max

enum class RecordPhase {
    IDLE,
    PREPARING,
    RECORDING,
    ERROR,
}

data class RecorderState(
    val phase: RecordPhase = RecordPhase.IDLE,
    val statusText: String = "",
    val inputLabel: String = "",
    val source: CaptureSource? = null,
    val sampleRate: Int = 0,
    val level: Float = 0f,
    val partial: String = "",
    val lines: List<TranscriptLine> = emptyList(),
    val elapsedMs: Long = 0L,
    val modelLabel: String = "",
    val rtf: Double? = null,
    val error: String? = null,
)

class RecordingRuntime(
    private val routes: AudioRouteController,
    private val sessions: SessionStore,
    private val models: ModelRepository,
    private val prefs: AppPreferences,
    private val transcribers: (AsrModel, File, String?) -> SpeechTranscriber = { model, dir, language ->
        SherpaStreamingTranscriber(model, dir, language)
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()
    private val _scan = MutableStateFlow(DeviceScan(emptyList(), false))
    val scan: StateFlow<DeviceScan> = _scan.asStateFlow()

    @Volatile
    private var stopRequested = false

    @Volatile
    private var activeRecord: AudioRecord? = null
    private var job: Job? = null

    fun refreshDevices() {
        _scan.value = routes.scan()
    }

    fun start(onStatus: (String) -> Unit) {
        if (job?.isActive == true) return
        stopRequested = false
        job = scope.launch {
            try {
                record(onStatus)
            } catch (error: Throwable) {
                if (coroutineContext.isActive) {
                    _state.update {
                        it.copy(
                            phase = RecordPhase.ERROR,
                            error = error.message ?: "Recording failed",
                            statusText = error.message ?: "Recording failed",
                            level = 0f,
                        )
                    }
                    onStatus(error.message ?: "Recording failed")
                }
            } finally {
                routes.release()
                activeRecord = null
            }
        }
    }

    fun stop() {
        stopRequested = true
        try {
            activeRecord?.stop()
        } catch (_: Throwable) {
            // The recorder may already be released.
        }
    }

    private suspend fun record(onStatus: (String) -> Unit) {
        val settings = prefs.snapshot()
        val model = settings.activeModel()
        _state.value = RecorderState(
            phase = RecordPhase.PREPARING,
            statusText = "Preparing ${model.title}",
            modelLabel = model.title,
        )
        onStatus("Preparing ${model.title}")

        refreshDevices()
        val source = if (settings.usePhoneMic) CaptureSource.PHONE else CaptureSource.GLASSES
        val selected = selectInput(source, settings.glassesName)
        if (!models.isReady(model)) {
            _state.update { it.copy(statusText = "Downloading ${model.title}") }
            onStatus("Downloading ${model.title}")
            models.ensure(model)
        }

        val routed = openRoute(source, selected, settings.glassesName)
        val label = routed.label
        _state.update {
            it.copy(
                statusText = "Loading ${model.title}",
                inputLabel = label,
                source = source,
            )
        }
        onStatus("Loading ${model.title}")

        val transcriber = transcribers(
            model,
            models.directory(model),
            if (model.passesLanguage) settings.language.code else null,
        )
        var session: OpenSession? = null
        var capture: Capture? = null
        val lines = mutableListOf<TranscriptLine>()
        var failure: String? = null
        val startedAt = SystemClock.elapsedRealtime()
        try {
            transcriber.start()
            capture = openCapture(source, routed.device)
            val rate = capture.sampleRate
            session = sessions.create(
                sampleRate = rate,
                inputLabel = label,
                source = source.name,
                modelId = model.id,
                modelTitle = model.title,
                language = settings.language.code,
            )
            _state.update {
                it.copy(
                    phase = RecordPhase.RECORDING,
                    statusText = "Recording",
                    inputLabel = label,
                    source = source,
                    sampleRate = rate,
                    lines = emptyList(),
                    partial = "",
                    error = null,
                )
            }
            onStatus("Recording · $label")
            val routeLost = AtomicBoolean(false)
            val expectedId = AtomicInteger(routed.deviceId)
            if (source == CaptureSource.GLASSES) {
                routes.watch { id ->
                    if (id != expectedId.get()) routeLost.set(true)
                }
            }
            var smoothed = 0f
            var samples = 0L
            var headerAt = 0L
            var reconnects = 0
            var mic = capture ?: error("Microphone did not start")
            val buffer = ByteArray(rate / 10 * 2)
            while (!stopRequested) {
                if (source == CaptureSource.GLASSES && routeLost.get()) {
                    reconnects += 1
                    if (reconnects > 3) {
                        failure = "Bluetooth route lost. Saved what was recorded."
                        break
                    }
                    mic.close()
                    activeRecord = null
                    val again = reconnectGlasses(settings.glassesName, expectedId, routeLost)
                    if (again == null) {
                        failure = "Bluetooth route lost. Saved what was recorded."
                        break
                    }
                    mic = openCapture(source, routes.device(again))
                    capture = mic
                    if (mic.sampleRate != rate) {
                        mic.close()
                        failure = "The headset sample rate changed. Saved what was recorded."
                        break
                    }
                    val refreshed = AudioRouteSelector.glassesLabel(
                        _scan.value.candidates.firstOrNull { it.id == again }?.productName.orEmpty(),
                    )
                    _state.update { it.copy(inputLabel = refreshed, statusText = "Recording") }
                    onStatus("Recording · $refreshed")
                }
                val read = mic.read(buffer)
                if (stopRequested) break
                if (read < 0) {
                    if (source == CaptureSource.GLASSES) {
                        routeLost.set(true)
                        continue
                    }
                    failure = "The microphone stopped. Saved what was recorded."
                    break
                }
                if (read == 0) continue
                reconnects = 0
                session.writePcm(buffer, read)
                samples += read / 2
                val elapsed = samples * 1000L / rate
                val now = SystemClock.elapsedRealtime()
                if (now - headerAt > 1_000L) {
                    session.updateHeader()
                    headerAt = now
                }
                val instant = pcm16Level(buffer, read)
                smoothed = if (instant > smoothed) instant else smoothed * 0.85f
                val update = transcriber.accept(pcm16ToFloat(buffer, read), rate)
                if (update.finalText != null) {
                    lines += TranscriptLine(elapsed, update.finalText)
                    session.writeTranscript(lines)
                }
                _state.update {
                    it.copy(
                        level = smoothed,
                        partial = update.partial,
                        lines = lines.toList(),
                        elapsedMs = elapsed,
                        rtf = transcriber.realTimeFactor(),
                        inputLabel = it.inputLabel,
                    )
                }
            }
            val tail = transcriber.finish()
            if (tail.finalText != null) {
                val elapsed = samples * 1000L / rate
                lines += TranscriptLine(elapsed, tail.finalText)
            }
            val rtf = transcriber.realTimeFactor()
            val duration = SystemClock.elapsedRealtime() - startedAt
            session.finish(duration, rtf, lines)
            session = null
            if (failure != null) {
                _state.update {
                    it.copy(
                        phase = RecordPhase.ERROR,
                        error = failure,
                        statusText = failure,
                        level = 0f,
                        partial = "",
                        lines = lines.toList(),
                        rtf = rtf,
                        elapsedMs = duration,
                    )
                }
                onStatus(failure)
            } else {
                _state.update {
                    it.copy(
                        phase = RecordPhase.IDLE,
                        statusText = "Saved",
                        level = 0f,
                        partial = "",
                        lines = lines.toList(),
                        rtf = rtf,
                        elapsedMs = duration,
                        error = null,
                    )
                }
                onStatus("Saved")
            }
        } finally {
            capture?.close()
            activeRecord = null
            transcriber.release()
            if (session != null) {
                val duration = SystemClock.elapsedRealtime() - startedAt
                session.finish(duration, transcriber.realTimeFactor(), lines)
            }
        }
    }

    private fun selectInput(source: CaptureSource, glassesName: String): Int {
        val scan = routes.scan()
        _scan.value = scan
        return when (source) {
            CaptureSource.PHONE -> {
                AudioRouteSelector.selectPhoneMicrophone(scan.candidates)?.id
                    ?: error("This phone has no built-in microphone the app can open.")
            }
            CaptureSource.GLASSES -> {
                AudioRouteSelector.selectGlasses(scan.candidates, glassesName)?.id
                    ?: error(
                        "No glasses microphone is connected. Pair the Ray-Ban Display " +
                            "in the Meta AI app, or switch to the phone microphone.",
                    )
            }
        }
    }

    private suspend fun openRoute(
        source: CaptureSource,
        deviceId: Int,
        glassesName: String,
    ): Routed {
        return when (source) {
            CaptureSource.PHONE -> {
                routes.applyPhoneMic()
                val device = routes.device(deviceId)
                Routed(
                    deviceId = deviceId,
                    device = device,
                    label = AudioRouteSelector.phoneLabel(),
                )
            }
            CaptureSource.GLASSES -> {
                val device = routes.device(deviceId)
                    ?: error("The glasses disappeared before recording could start.")
                if (!routes.applyGlasses(device)) {
                    error("Android refused the Bluetooth headset route.")
                }
                if (!awaitRoute(deviceId)) {
                    error(
                        "The Bluetooth route did not settle. Wake the glasses, " +
                            "keep them near the phone, and try again.",
                    )
                }
                refreshDevices()
                val name = _scan.value.candidates.firstOrNull { it.id == deviceId }?.productName
                    ?: device.productName?.toString().orEmpty()
                val matched = AudioRouteSelector.selectGlasses(_scan.value.candidates, glassesName)
                val label = AudioRouteSelector.glassesLabel(matched?.productName ?: name)
                Routed(deviceId = deviceId, device = routes.device(deviceId) ?: device, label = label)
            }
        }
    }

    private suspend fun reconnectGlasses(
        glassesName: String,
        expectedId: AtomicInteger,
        routeLost: AtomicBoolean,
    ): Int? {
        refreshDevices()
        val candidate = AudioRouteSelector.selectGlasses(_scan.value.candidates, glassesName)
            ?: return null
        val device = routes.device(candidate.id) ?: return null
        expectedId.set(candidate.id)
        routeLost.set(false)
        if (!routes.applyGlasses(device)) return null
        if (!awaitRoute(candidate.id)) return null
        routeLost.set(false)
        return candidate.id
    }

    private suspend fun awaitRoute(deviceId: Int, timeoutMs: Long = 2_500L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (routes.communicationDeviceId() == deviceId) {
                delay(400)
                return routes.communicationDeviceId() == deviceId
            }
            delay(100)
        }
        return false
    }

    private fun openCapture(
        source: CaptureSource,
        device: AudioDeviceInfo?,
    ): Capture {
        val preferred = chooseSampleRate(device)
        val rates = listOf(preferred) + listOf(16_000, 8_000).filter { it != preferred }
        var lastError: String? = null
        for (rate in rates) {
            val capture = tryOpen(source, rate, device)
            if (capture != null) return capture
            lastError = "Could not open the microphone at $rate Hz."
        }
        error(lastError ?: "Could not open the microphone.")
    }

    private fun tryOpen(source: CaptureSource, sampleRate: Int, device: AudioDeviceInfo?): Capture? {
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return null
        val buffer = max(min * 2, sampleRate / 5 * 2)
        val audioSource = if (source == CaptureSource.GLASSES) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(buffer)
                .build()
        } catch (_: Throwable) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        if (device != null) {
            record.setPreferredDevice(device)
        }
        try {
            record.startRecording()
        } catch (_: Throwable) {
            record.release()
            return null
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return null
        }
        activeRecord = record
        return Capture(record, sampleRate)
    }

    private fun chooseSampleRate(device: AudioDeviceInfo?): Int {
        val rates = device?.sampleRates?.toSet().orEmpty()
        return when {
            rates.isEmpty() || 16_000 in rates -> 16_000
            8_000 in rates -> 8_000
            else -> 16_000
        }
    }

    private class Capture(
        val record: AudioRecord,
        val sampleRate: Int,
    ) {
        private var closed = false

        fun read(buffer: ByteArray): Int = record.read(buffer, 0, buffer.size)

        fun close() {
            if (closed) return
            closed = true
            try {
                record.stop()
            } catch (_: Throwable) {
                // Already stopped.
            }
            record.release()
        }
    }

    private data class Routed(
        val deviceId: Int,
        val device: AudioDeviceInfo?,
        val label: String,
    )
}

private fun OpenSession.writePcm(pcm: ByteArray, length: Int) {
    wav.write(pcm, length)
}
