package com.glasses.hfptranscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {
    @Test
    fun silenceHasNoLevel() {
        assertEquals(0f, pcm16Level(ByteArray(8), 8))
        assertEquals(0f, pcm16Level(byteArrayOf(1), 1))
    }

    @Test
    fun fullScaleSampleReachesTheTopOfTheMeter() {
        val negativeFullScale = byteArrayOf(0x00, 0x80.toByte())
        assertEquals(1f, pcm16Level(negativeFullScale, 2), 0.0001f)
        assertEquals(-1f, pcm16ToFloat(negativeFullScale, 2)[0], 0.0001f)
    }

    @Test
    fun positiveSampleIsLittleEndian() {
        val sample = byteArrayOf(0xFF.toByte(), 0x7F)
        assertEquals(32767f / 32768f, pcm16ToFloat(sample, 2)[0], 0.0001f)
    }
}
