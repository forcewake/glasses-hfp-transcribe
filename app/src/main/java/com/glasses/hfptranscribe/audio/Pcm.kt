package com.glasses.hfptranscribe.audio

import kotlin.math.sqrt

/** RMS of little-endian PCM16, scaled to 0..1. */
fun pcm16Level(pcm: ByteArray, length: Int): Float {
    val samples = length / 2
    if (samples <= 0) return 0f
    var sum = 0.0
    var index = 0
    val limit = samples * 2
    while (index + 1 < limit) {
        val value = pcm16Sample(pcm, index)
        sum += value.toDouble() * value.toDouble()
        index += 2
    }
    val rms = sqrt(sum / samples)
    return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
}

/** Little-endian PCM16 to floats in -1..1, which is what sherpa-onnx expects. */
fun pcm16ToFloat(pcm: ByteArray, length: Int): FloatArray {
    val even = length - (length % 2)
    val out = FloatArray(even / 2)
    var index = 0
    var sampleIndex = 0
    while (index + 1 < even) {
        out[sampleIndex] = pcm16Sample(pcm, index) / 32768f
        index += 2
        sampleIndex++
    }
    return out
}

private fun pcm16Sample(pcm: ByteArray, index: Int): Int {
    val packed = (pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)
    return packed.toShort().toInt()
}
