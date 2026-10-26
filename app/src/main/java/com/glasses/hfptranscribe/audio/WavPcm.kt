package com.glasses.hfptranscribe.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono PCM16 audio, little-endian. */
data class WavPcm(
    val sampleRate: Int,
    val pcm: ByteArray,
)

/**
 * Reads a PCM WAV and returns mono 16-bit samples. Stereo is averaged.
 * Extra chunks (LIST, fact) are skipped. Used by the debug file source.
 */
fun readWavPcm16Mono(file: File): WavPcm {
    if (!file.isFile) error("Audio file not found: ${file.path}")
    RandomAccessFile(file, "r").use { raf ->
        if (raf.readTag() != "RIFF") error("Not a RIFF WAV file: ${file.name}")
        raf.readLeInt()
        if (raf.readTag() != "WAVE") error("Not a WAVE file: ${file.name}")
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var data: ByteArray? = null
        while (raf.filePointer + 8 <= raf.length()) {
            val id = raf.readTag()
            val size = raf.readLeInt()
            if (size < 0) error("Bad WAV chunk $id")
            val start = raf.filePointer
            when (id) {
                "fmt " -> {
                    format = raf.readLeUnsignedShort()
                    channels = raf.readLeUnsignedShort()
                    rate = raf.readLeInt()
                    raf.readLeInt()
                    raf.readLeUnsignedShort()
                    bits = raf.readLeUnsignedShort()
                }
                "data" -> {
                    val bytes = ByteArray(size)
                    raf.readFully(bytes)
                    data = bytes
                }
            }
            val consumed = (raf.filePointer - start).toInt()
            val skip = size - consumed
            if (skip > 0) raf.seek(raf.filePointer + skip)
            if (size % 2 == 1 && raf.filePointer < raf.length()) {
                raf.seek(raf.filePointer + 1)
            }
        }
        if (format != 1) error("Only PCM WAV files can be transcribed")
        if (bits != 16) error("Only 16-bit WAV files can be transcribed")
        if (channels != 1 && channels != 2) error("Only mono and stereo WAV files can be transcribed")
        if (rate <= 0) error("WAV sample rate is missing")
        val pcm = data ?: error("WAV file has no audio data")
        return WavPcm(sampleRate = rate, pcm = if (channels == 1) pcm else downmixStereo(pcm))
    }
}

private fun downmixStereo(pcm: ByteArray): ByteArray {
    val frames = pcm.size / 4
    val out = ByteArray(frames * 2)
    val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val output = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frames) {
        val left = input.short.toInt()
        val right = input.short.toInt()
        output.putShort(((left + right) / 2).toShort())
    }
    return out
}

private fun RandomAccessFile.readTag(): String {
    val bytes = ByteArray(4)
    if (read(bytes) != 4) error("WAV ended early")
    return String(bytes, Charsets.US_ASCII)
}

private fun RandomAccessFile.readLeInt(): Int {
    val bytes = ByteArray(4)
    if (read(bytes) != 4) error("WAV ended early")
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun RandomAccessFile.readLeUnsignedShort(): Int {
    val bytes = ByteArray(2)
    if (read(bytes) != 2) error("WAV ended early")
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
}
