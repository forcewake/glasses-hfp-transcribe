package com.glasses.hfptranscribe.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFormatterTest {
    @Test
    fun formatsSubHourTimestamps() {
        assertEquals("00:00.000", formatTimestamp(0))
        assertEquals("00:00.999", formatTimestamp(999))
        assertEquals("00:01.000", formatTimestamp(1_000))
        assertEquals("00:12.500", formatTimestamp(12_500))
        assertEquals("01:00.000", formatTimestamp(60_000))
    }

    @Test
    fun formatsHourTimestampsAndClampsNegatives() {
        assertEquals("1:02:03.004", formatTimestamp(3_600_000L + 2 * 60_000L + 3_000L + 4))
        assertEquals("00:00.000", formatTimestamp(-20))
    }

    @Test
    fun writesOneTimestampedLinePerPhrase() {
        val text = formatTranscript(
            listOf(
                TranscriptLine(1_200, "Привет"),
                TranscriptLine(4_200, "hello there"),
            ),
        )
        assertEquals("[00:01.200] Привет\n[00:04.200] hello there\n", text)
    }

    @Test
    fun dropsBlankPhrasesAndTrimsWhitespace() {
        val text = formatTranscript(
            listOf(
                TranscriptLine(0, "   "),
                TranscriptLine(1_500, "  hallo  "),
                TranscriptLine(2_000, ""),
            ),
        )
        assertEquals("[00:01.500] hallo\n", text)
    }

    @Test
    fun emptyTranscriptIsAnEmptyString() {
        assertEquals("", formatTranscript(emptyList()))
    }
}
