package com.glasses.hfptranscribe.session

import com.glasses.hfptranscribe.audio.WavWriter
import com.glasses.hfptranscribe.transcript.TranscriptLine
import com.glasses.hfptranscribe.transcript.formatTranscript
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionSummary(
    val id: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val inputLabel: String,
    val source: String,
    val modelId: String,
    val modelTitle: String,
    val language: String,
    val rtf: Double?,
    val wav: File,
    val transcript: File,
)

class SessionStore(private val root: File) {
    fun list(): List<SessionSummary> {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { read(it) }.sortedByDescending { it.startedAtEpochMs }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun create(
        sampleRate: Int,
        inputLabel: String,
        source: String,
        modelId: String,
        modelTitle: String,
        language: String,
    ): OpenSession {
        val started = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(started))
        val dir = File(root, id)
        dir.mkdirs()
        val meta = JSONObject()
            .put("id", id)
            .put("startedAtEpochMs", started)
            .put("durationMs", 0)
            .put("sampleRate", sampleRate)
            .put("inputLabel", inputLabel)
            .put("source", source)
            .put("modelId", modelId)
            .put("modelTitle", modelTitle)
            .put("language", language)
        File(dir, META).writeText(meta.toString(2))
        return OpenSession(
            id = id,
            dir = dir,
            wav = WavWriter(File(dir, WAV), sampleRate),
            meta = meta,
        )
    }

    private fun read(dir: File): SessionSummary? {
        val metaFile = File(dir, META)
        val wav = File(dir, WAV)
        val transcript = File(dir, TRANSCRIPT)
        if (!metaFile.isFile || !wav.isFile) return null
        return try {
            val meta = JSONObject(metaFile.readText())
            SessionSummary(
                id = meta.getString("id"),
                startedAtEpochMs = meta.getLong("startedAtEpochMs"),
                durationMs = meta.optLong("durationMs"),
                sampleRate = meta.optInt("sampleRate", 16_000),
                inputLabel = meta.optString("inputLabel"),
                source = meta.optString("source"),
                modelId = meta.optString("modelId"),
                modelTitle = meta.optString("modelTitle"),
                language = meta.optString("language"),
                rtf = if (meta.has("rtf") && !meta.isNull("rtf")) meta.getDouble("rtf") else null,
                wav = wav,
                transcript = transcript,
            )
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val WAV = "audio.wav"
        const val TRANSCRIPT = "transcript.txt"
        const val META = "meta.json"
    }
}

class OpenSession(
    val id: String,
    val dir: File,
    val wav: WavWriter,
    private val meta: JSONObject,
) {
    fun writeTranscript(lines: List<TranscriptLine>) {
        File(dir, SessionStore.TRANSCRIPT).writeText(formatTranscript(lines))
    }

    fun updateHeader() {
        wav.updateHeader()
    }

    fun finish(durationMs: Long, rtf: Double?, lines: List<TranscriptLine>) {
        wav.close()
        writeTranscript(lines)
        meta.put("durationMs", durationMs)
        if (rtf != null) meta.put("rtf", rtf)
        File(dir, SessionStore.META).writeText(meta.toString(2))
    }
}
