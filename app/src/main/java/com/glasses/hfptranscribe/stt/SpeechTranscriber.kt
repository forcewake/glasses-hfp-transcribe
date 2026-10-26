package com.glasses.hfptranscribe.stt

/**
 * Streaming on-device speech recognition.
 *
 * [SherpaStreamingTranscriber] is the engine this app ships. A second engine
 * (for example Vosk, for a small Dutch model) can implement this interface
 * without changing capture or the session files.
 */
interface SpeechTranscriber {
    val engineId: String

    fun start()

    /** Mono PCM in the range -1..1, at [sampleRate]. */
    fun accept(samples: FloatArray, sampleRate: Int): RecognitionUpdate

    fun finish(): RecognitionUpdate

    fun release()

    /** Decode time divided by audio time. Null until some audio has been accepted. */
    fun realTimeFactor(): Double?
}

data class RecognitionUpdate(
    val partial: String,
    val finalText: String?,
)
