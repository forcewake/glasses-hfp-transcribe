package com.glasses.hfptranscribe.transcript

data class TranscriptLine(
    val offsetMillis: Long,
    val text: String,
)

/** Session clock as `mm:ss.mmm`, or `h:mm:ss.mmm` once an hour has passed. */
fun formatTimestamp(offsetMillis: Long): String {
    val clamped = offsetMillis.coerceAtLeast(0)
    val hours = clamped / 3_600_000
    val minutes = (clamped % 3_600_000) / 60_000
    val seconds = (clamped % 60_000) / 1_000
    val millis = clamped % 1_000
    return if (hours > 0) {
        "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    } else {
        "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}

/**
 * One finalized phrase per line: `[00:04.200] text`.
 * Blank phrases are dropped. The timestamp is when the phrase was committed.
 */
fun formatTranscript(lines: List<TranscriptLine>): String {
    val body = lines.mapNotNull { line ->
        val text = line.text.trim()
        if (text.isEmpty()) null else "[${formatTimestamp(line.offsetMillis)}] $text"
    }.joinToString(separator = "\n")
    return if (body.isEmpty()) "" else "$body\n"
}
