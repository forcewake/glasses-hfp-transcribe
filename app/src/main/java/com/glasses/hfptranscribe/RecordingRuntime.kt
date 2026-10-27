package com.glasses.hfptranscribe

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.glasses.hfptranscribe.audio.AudioRouteController
import com.glasses.hfptranscribe.audio.AudioRouteSelector
import com.glasses.hfptranscribe.audio.OpenedGlasses
import com.glasses.hfptranscribe.audio.RouteException
import com.glasses.hfptranscribe.audio.CaptureSource
import com.glasses.hfptranscribe.audio.DeviceScan
import com.glasses.hfptranscribe.audio.InputKind
import com.glasses.hfptranscribe.audio.pcm16Level
import com.glasses.hfptranscribe.audio.pcm16ToFloat
import com.glasses.hfptranscribe.audio.readWavPcm16Mono
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
import kotlin.math.min
import java.util.concurrent.atomic.AtomicBoolean
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
    val diagnostics: String? = null,
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
                    val route = error as? RouteException
                    val summary = route?.summary ?: error.message ?: "Recording failed"
                    _state.update {
                        it.copy(
                            phase = RecordPhase.ERROR,
                            error = summary,
                            diagnostics = route?.diagnostics,
                            statusText = summary,
                            level = 0f,
                        )
                    }
                    onStatus(summary)
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

    /**
     * Streams a WAV through the same recognizer and session files as the mic.
     * Only the debug entry point calls this. It is not a control on the main screen.
     */
    fun transcribeFile(wav: File, model: AsrModel, onStatus: (String) -> Unit) {
        if (job?.isActive == true) return
        stopRequested = false
        job = scope.launch {
            try {
                runFile(wav, model, onStatus)
            } catch (error: Throwable) {
                if (coroutineContext.isActive) {
                    val message = error.message ?: "Could not transcribe that file"
                    _state.update {
                        it.copy(
                            phase = RecordPhase.ERROR,
                            error = message,
                            diagnostics = null,
                            statusText = message,
                            level = 0f,
                        )
                    }
                    onStatus(message)
                    Log.e(TRANSCRIPT_LOG, message, error)
                }
            } finally {
                routes.release()
                activeRecord = null
            }
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

        val routed = openRoute(source, selected)
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
        var audioMs = 0L
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
            if (source == CaptureSource.GLASSES) {
                routes.watchRoute(routed.sinkId, routed.legacySco) { routeLost.set(true) }
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
                    val again = reconnectGlasses(settings.glassesName)
                    if (again == null) {
                        failure = "Bluetooth route lost. Saved what was recorded."
                        break
                    }
                    routeLost.set(false)
                    routes.watchRoute(again.sinkId, again.legacySco) { routeLost.set(true) }
                    mic = openCapture(source, again.input)
                    capture = mic
                    if (mic.sampleRate != rate) {
                        mic.close()
                        failure = "The headset sample rate changed. Saved what was recorded."
                        break
                    }
                    val refreshed = AudioRouteSelector.glassesLabel(again.productName)
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
                audioMs = elapsed
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
            if (tail.finalText != null && lines.lastOrNull()?.text != tail.finalText) {
                lines += TranscriptLine(audioMs, tail.finalText)
            }
            val rtf = transcriber.realTimeFactor()
            session.finish(audioMs, rtf, lines)
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
                        elapsedMs = audioMs,
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
                        elapsedMs = audioMs,
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
                session.finish(audioMs, transcriber.realTimeFactor(), lines)
            }
        }
    }

    private suspend fun runFile(wav: File, model: AsrModel, onStatus: (String) -> Unit) {
        val settings = prefs.snapshot()
        _state.value = RecorderState(
            phase = RecordPhase.PREPARING,
            statusText = "Preparing ${model.title}",
            modelLabel = model.title,
            inputLabel = FILE_INPUT_LABEL,
        )
        onStatus("Preparing ${model.title}")
        if (!models.isReady(model)) {
            _state.update { it.copy(statusText = "Downloading ${model.title}") }
            onStatus("Downloading ${model.title}")
            models.ensure(model)
        }
        _state.update { it.copy(statusText = "Loading ${model.title}") }
        onStatus("Loading ${model.title}")
        val audio = withContext(Dispatchers.IO) { readWavPcm16Mono(wav) }
        val transcriber = transcribers(
            model,
            models.directory(model),
            if (model.passesLanguage) settings.language.code else null,
        )
        var session: OpenSession? = null
        val lines = mutableListOf<TranscriptLine>()
        var audioMs = 0L
        try {
            transcriber.start()
            val rate = audio.sampleRate
            session = sessions.create(
                sampleRate = rate,
                inputLabel = FILE_INPUT_LABEL,
                source = "FILE",
                modelId = model.id,
                modelTitle = model.title,
                language = settings.language.code,
            )
            _state.update {
                it.copy(
                    phase = RecordPhase.RECORDING,
                    statusText = "Recording",
                    inputLabel = FILE_INPUT_LABEL,
                    sampleRate = rate,
                    lines = emptyList(),
                    partial = "",
                    error = null,
                    modelLabel = model.title,
                )
            }
            onStatus("Recording · $FILE_INPUT_LABEL")
            val pcm = audio.pcm
            val chunkBytes = max(2, rate / 10 * 2)
            var offset = 0
            var samples = 0L
            var headerAt = 0L
            var smoothed = 0f
            var lastPartial = ""
            while (offset < pcm.size && !stopRequested) {
                val count = min(chunkBytes, pcm.size - offset)
                val even = count - (count % 2)
                if (even <= 0) break
                val slice = pcm.copyOfRange(offset, offset + even)
                offset += even
                val decodeStarted = System.nanoTime()
                session.writePcm(slice, even)
                samples += even / 2
                val elapsed = samples * 1000L / rate
                audioMs = elapsed
                val now = SystemClock.elapsedRealtime()
                if (now - headerAt > 1_000L) {
                    session.updateHeader()
                    headerAt = now
                }
                val instant = pcm16Level(slice, even)
                smoothed = if (instant > smoothed) instant else smoothed * 0.85f
                val update = transcriber.accept(pcm16ToFloat(slice, even), rate)
                if (update.finalText != null) {
                    lines += TranscriptLine(elapsed, update.finalText)
                    session.writeTranscript(lines)
                    lastPartial = ""
                    Log.i(TRANSCRIPT_LOG, "final: ${update.finalText}")
                } else if (update.partial.isNotBlank() && update.partial != lastPartial) {
                    lastPartial = update.partial
                    Log.i(TRANSCRIPT_LOG, "partial: ${update.partial}")
                }
                _state.update {
                    it.copy(
                        level = smoothed,
                        partial = update.partial,
                        lines = lines.toList(),
                        elapsedMs = elapsed,
                        rtf = transcriber.realTimeFactor(),
                    )
                }
                val decodeMs = (System.nanoTime() - decodeStarted) / 1_000_000
                val chunkMs = even / 2 * 1000L / rate
                if (chunkMs > decodeMs) delay(chunkMs - decodeMs)
            }
            val tail = transcriber.finish()
            if (tail.finalText != null && lines.lastOrNull()?.text != tail.finalText) {
                lines += TranscriptLine(audioMs, tail.finalText)
                Log.i(TRANSCRIPT_LOG, "final: ${tail.finalText}")
            }
            val rtf = transcriber.realTimeFactor()
            session.finish(audioMs, rtf, lines)
            session = null
            Log.i(
                TRANSCRIPT_LOG,
                "saved lines=${lines.size} rtf=${rtf?.let { "%.3f".format(it) } ?: "n/a"} " +
                    "text=${lines.joinToString(" | ") { it.text }}",
            )
            _state.update {
                it.copy(
                    phase = RecordPhase.IDLE,
                    statusText = "Saved",
                    level = 0f,
                    partial = "",
                    lines = lines.toList(),
                    rtf = rtf,
                    elapsedMs = audioMs,
                    error = null,
                    inputLabel = FILE_INPUT_LABEL,
                    modelLabel = model.title,
                )
            }
            onStatus("Saved")
        } finally {
            transcriber.release()
            if (session != null) {
                session.finish(audioMs, transcriber.realTimeFactor(), lines)
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
    ): Routed {
        return when (source) {
            CaptureSource.PHONE -> {
                routes.applyPhoneMic()
                val scan = routes.scan()
                _scan.value = scan
                val headset = scan.candidates.any { it.kind == InputKind.BLUETOOTH_SCO }
                Routed(
                    deviceId = deviceId,
                    device = routes.device(deviceId),
                    label = AudioRouteSelector.phoneFallbackLabel(headset),
                )
            }
            CaptureSource.GLASSES -> {
                val opened = routes.openGlasses(deviceId)
                refreshDevices()
                Routed(
                    deviceId = opened.input?.id ?: deviceId,
                    device = opened.input,
                    label = AudioRouteSelector.glassesLabel(opened.productName),
                    sinkId = opened.sinkId,
                    legacySco = opened.legacySco,
                )
            }
        }
    }

    private suspend fun reconnectGlasses(glassesName: String): OpenedGlasses? {
        refreshDevices()
        val candidate = AudioRouteSelector.selectGlasses(_scan.value.candidates, glassesName)
            ?: return null
        return try {
            routes.openGlasses(candidate.id)
        } catch (_: RouteException) {
            null
        }
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
        val sinkId: Int? = null,
        val legacySco: Boolean = false,
    )
}

private fun OpenSession.writePcm(pcm: ByteArray, length: Int) {
    wav.write(pcm, length)
}

internal const val FILE_INPUT_LABEL = "Audio file"
private const val TRANSCRIPT_LOG = "GlassesTranscript"
