package com.glasses.hfptranscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmTest {
    @Test
    fun readsMonoPcmWrittenByTheSessionWriter() {
        val file = File.createTempFile("mono", ".wav")
        val pcm = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x7F)
        WavWriter(file, sampleRate = 16_000).use { it.write(pcm) }
        val read = readWavPcm16Mono(file)
        assertEquals(16_000, read.sampleRate)
        assertEquals(pcm.toList(), read.pcm.toList())
        file.delete()
    }

    @Test
    fun averagesStereoFramesIntoMono() {
        val file = File.createTempFile("stereo", ".wav")
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1000)
            .putShort(3000)
            .putShort(-4)
            .putShort(8)
            .array()
        WavWriter(file, sampleRate = 8_000, channels = 2).use { it.write(pcm) }
        val read = readWavPcm16Mono(file)
        assertEquals(8_000, read.sampleRate)
        val samples = ByteBuffer.wrap(read.pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2000, samples.short.toInt())
        assertEquals(2, samples.short.toInt())
        file.delete()
    }

    @Test
    fun missingFileFailsClearly() {
        try {
            readWavPcm16Mono(File("missing-audio.wav"))
            fail("expected an error")
        } catch (error: IllegalStateException) {
            assertEquals(true, error.message?.contains("not found") == true)
        }
    }
}
