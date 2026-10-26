package com.glasses.hfptranscribe.stt

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx streaming recognizer. PCM from the SCO link is passed straight
 * through; the library resamples when a model expects another rate (T-one is 8 kHz).
 */
class SherpaStreamingTranscriber(
    private val model: AsrModel,
    private val modelDir: File,
    private val languageCode: String?,
) : SpeechTranscriber {
    override val engineId: String = "sherpa-onnx"

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var decodeNanos: Long = 0
    private var audioSamples: Long = 0
    private var audioRate: Int = 16_000

    override fun start() {
        val config = recognizerConfig()
        val created = OnlineRecognizer(config = config)
        val online = created.createStream()
        recognizer = created
        stream = online
        applyLanguage()
    }

    override fun accept(samples: FloatArray, sampleRate: Int): RecognitionUpdate {
        val online = stream ?: return RecognitionUpdate("", null)
        val decoder = recognizer ?: return RecognitionUpdate("", null)
        if (samples.isEmpty()) return RecognitionUpdate(currentPartial(decoder, online), null)
        audioRate = sampleRate
        audioSamples += samples.size
        val started = System.nanoTime()
        online.acceptWaveform(samples, sampleRate)
        decodeReady(decoder, online)
        decodeNanos += System.nanoTime() - started
        return snapshot(decoder, online)
    }

    override fun finish(): RecognitionUpdate {
        val online = stream ?: return RecognitionUpdate("", null)
        val decoder = recognizer ?: return RecognitionUpdate("", null)
        val started = System.nanoTime()
        online.inputFinished()
        decodeReady(decoder, online)
        decodeNanos += System.nanoTime() - started
        val text = decoder.getResult(online).text.trim()
        return RecognitionUpdate(partial = "", finalText = text.ifBlank { null })
    }

    override fun release() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
    }

    override fun realTimeFactor(): Double? {
        if (audioSamples <= 0 || audioRate <= 0 || decodeNanos <= 0) return null
        val audioSeconds = audioSamples.toDouble() / audioRate.toDouble()
        if (audioSeconds <= 0.0) return null
        return (decodeNanos / 1_000_000_000.0) / audioSeconds
    }

    private fun snapshot(decoder: OnlineRecognizer, online: OnlineStream): RecognitionUpdate {
        val text = decoder.getResult(online).text.trim()
        if (!decoder.isEndpoint(online)) {
            return RecognitionUpdate(partial = text, finalText = null)
        }
        decoder.reset(online)
        applyLanguage()
        return RecognitionUpdate(partial = "", finalText = text.ifBlank { null })
    }

    private fun currentPartial(decoder: OnlineRecognizer, online: OnlineStream): String {
        return decoder.getResult(online).text.trim()
    }

    private fun decodeReady(decoder: OnlineRecognizer, online: OnlineStream) {
        var steps = 0
        while (decoder.isReady(online) && steps < MAX_DECODE_STEPS) {
            decoder.decode(online)
            steps++
        }
    }

    private fun applyLanguage() {
        if (!model.passesLanguage) return
        val code = languageCode ?: return
        stream?.setOption("language", code)
    }

    private fun recognizerConfig(): OnlineRecognizerConfig {
        val tokens = File(modelDir, model.tokens)
        require(tokens.isFile) { "Missing ${tokens.name} in ${modelDir.path}" }
        val modelConfig = if (model.toneModel != null) {
            val tone = File(modelDir, model.toneModel)
            require(tone.isFile) { "Missing ${tone.name}" }
            OnlineModelConfig(
                toneCtc = OnlineToneCtcModelConfig(model = tone.absolutePath),
                tokens = tokens.absolutePath,
                numThreads = model.threads,
                debug = false,
                provider = "cpu",
                modelType = model.modelType,
            )
        } else {
            val encoder = File(modelDir, model.encoder)
            val decoder = File(modelDir, model.decoder)
            val joiner = File(modelDir, model.joiner)
            require(encoder.isFile && decoder.isFile && joiner.isFile) {
                "Missing transducer files in ${modelDir.path}"
            }
            OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = model.threads,
                debug = false,
                provider = "cpu",
                modelType = model.modelType,
            )
        }
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
            modelConfig = modelConfig,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 0.8f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
    }

    private companion object {
        const val MAX_DECODE_STEPS = 10_000
    }
}
