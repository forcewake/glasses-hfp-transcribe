package com.glasses.hfptranscribe.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class WavWriterTest {
    @Test
    fun emptyFileHasAValidHeader() {
        val file = tempWav()
        WavWriter(file, sampleRate = 16_000).close()
        val wav = readWav(file)
        assertEquals(36, wav.chunkSize)
        assertEquals(0, wav.dataSize)
        assertEquals(16_000, wav.sampleRate)
        assertEquals(1, wav.channels)
        assertEquals(16, wav.bitsPerSample)
        assertEquals(1, wav.audioFormat)
        assertEquals(32_000, wav.byteRate)
        assertEquals(2, wav.blockAlign)
        assertEquals(44, file.length().toInt())
    }

    @Test
    fun roundTripsLittleEndianPcm() {
        val file = tempWav()
        val pcm = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        WavWriter(file, sampleRate = 16_000).use { it.write(pcm) }
        val wav = readWav(file)
        assertEquals(6, wav.dataSize)
        assertEquals(42, wav.chunkSize)
        assertArrayEquals(pcm, wav.pcm)
        assertEquals(listOf(0, 32767, -32768), wav.samples)
    }

    @Test
    fun multipleWritesAccumulateAndRefreshTheHeader() {
        val file = tempWav()
        WavWriter(file, sampleRate = 8_000).use { writer ->
            writer.write(byteArrayOf(0x01, 0x00))
            writer.updateHeader()
            writer.write(byteArrayOf(0x02, 0x00, 0x03, 0x00))
        }
        val wav = readWav(file)
        assertEquals(8_000, wav.sampleRate)
        assertEquals(6, wav.dataSize)
        assertEquals(listOf(1, 2, 3), wav.samples)
        assertEquals(50, file.length().toInt())
    }

    @Test
    fun oddLengthDropsTheTrailingByte() {
        val file = tempWav()
        WavWriter(file, sampleRate = 16_000).use { writer ->
            writer.write(byteArrayOf(0x34, 0x12, 0x55))
        }
        val wav = readWav(file)
        assertEquals(2, wav.dataSize)
        assertEquals(listOf(0x1234), wav.samples)
    }

    private fun tempWav(): File = File.createTempFile("session", ".wav").apply { deleteOnExit() }

    private fun readWav(file: File): ParsedWav {
        RandomAccessFile(file, "r").use { raf ->
            assertEquals("RIFF", readAscii(raf, 4))
            val chunkSize = readLeInt(raf)
            assertEquals("WAVE", readAscii(raf, 4))
            assertEquals("fmt ", readAscii(raf, 4))
            assertEquals(16, readLeInt(raf))
            val audioFormat = readLeShort(raf)
            val channels = readLeShort(raf)
            val sampleRate = readLeInt(raf)
            val byteRate = readLeInt(raf)
            val blockAlign = readLeShort(raf)
            val bits = readLeShort(raf)
            assertEquals("data", readAscii(raf, 4))
            val dataSize = readLeInt(raf)
            val pcm = ByteArray(dataSize)
            raf.readFully(pcm)
            val samples = pcm.toList().chunked(2).map { (low, high) ->
                ((low.toInt() and 0xff) or (high.toInt() shl 8)).toShort().toInt()
            }
            return ParsedWav(
                chunkSize = chunkSize,
                audioFormat = audioFormat,
                channels = channels,
                sampleRate = sampleRate,
                byteRate = byteRate,
                blockAlign = blockAlign,
                bitsPerSample = bits,
                dataSize = dataSize,
                pcm = pcm,
                samples = samples,
            )
        }
    }

    private fun readAscii(raf: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        raf.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readLeInt(raf: RandomAccessFile): Int {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)
    }

    private fun readLeShort(raf: RandomAccessFile): Int {
        val bytes = ByteArray(2)
        raf.readFully(bytes)
        return (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
    }

    private data class ParsedWav(
        val chunkSize: Int,
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val byteRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val dataSize: Int,
        val pcm: ByteArray,
        val samples: List<Int>,
    )
}
