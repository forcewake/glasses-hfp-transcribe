package com.glasses.hfptranscribe.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes a mono or stereo PCM WAV. The header is rewritten as samples arrive
 * so a file is playable even if the process is killed mid-session.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Int = 0
    private var closed = false

    val bytesWritten: Int get() = dataBytes

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        require(bitsPerSample == 16) { "only PCM16 is supported" }
        file.parentFile?.mkdirs()
        raf.setLength(0)
        writeHeader()
    }

    fun write(pcm: ByteArray, length: Int = pcm.size) {
        check(!closed) { "WAV writer is closed" }
        require(length >= 0 && length <= pcm.size) { "length out of range" }
        val even = length - (length % 2)
        if (even == 0) return
        raf.write(pcm, 0, even)
        dataBytes += even
    }

    fun updateHeader() {
        check(!closed) { "WAV writer is closed" }
        val position = raf.filePointer
        raf.seek(0)
        writeHeader()
        raf.seek(position)
    }

    override fun close() {
        if (closed) return
        updateHeader()
        raf.close()
        closed = true
    }

    private fun writeHeader() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        raf.writeBytes("RIFF")
        writeLeInt(36 + dataBytes)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeLeInt(16)
        writeLeShort(1)
        writeLeShort(channels)
        writeLeInt(sampleRate)
        writeLeInt(byteRate)
        writeLeShort(blockAlign)
        writeLeShort(bitsPerSample)
        raf.writeBytes("data")
        writeLeInt(dataBytes)
    }

    private fun writeLeInt(value: Int) {
        raf.write(value and 0xff)
        raf.write((value shr 8) and 0xff)
        raf.write((value shr 16) and 0xff)
        raf.write((value shr 24) and 0xff)
    }

    private fun writeLeShort(value: Int) {
        raf.write(value and 0xff)
        raf.write((value shr 8) and 0xff)
    }
}
