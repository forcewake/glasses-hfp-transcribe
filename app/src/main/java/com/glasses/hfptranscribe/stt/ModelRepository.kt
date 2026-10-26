package com.glasses.hfptranscribe.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

data class DownloadProgress(
    val modelId: String,
    val label: String,
    val fraction: Float?,
)

class ModelRepository(context: Context) {
    private val root = File(context.filesDir, "models")
    private val cache = File(context.cacheDir, "model-downloads")
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    fun directory(model: AsrModel): File = File(root, model.id)

    fun isReady(model: AsrModel): Boolean {
        val dir = directory(model)
        return model.requiredFiles.all { name ->
            val file = File(dir, name)
            file.isFile && file.length() > 0L
        }
    }

    suspend fun ensure(model: AsrModel) {
        if (isReady(model)) return
        download(model)
    }

    suspend fun download(model: AsrModel) {
        mutex.withLock {
            if (isReady(model)) return
            withContext(Dispatchers.IO) {
                val dest = directory(model)
                val usable = root.usableSpace
                if (usable in 1 until model.freeSpaceBytes) {
                    error(
                        "Not enough free space for ${model.title}. " +
                            "Leave about ${megabytes(model.freeSpaceBytes)} free and try again.",
                    )
                }
                cache.mkdirs()
                val archive = File(cache, model.archiveName)
                try {
                    downloadArchive(model, archive)
                    _progress.value = DownloadProgress(model.id, "Extracting ${model.title}", null)
                    extractRequired(archive, dest, model.requiredFiles.toSet())
                    val missing = model.requiredFiles.filter { !File(dest, it).isFile }
                    if (missing.isNotEmpty()) {
                        val found = dest.listFiles()?.joinToString { it.name }.orEmpty()
                        dest.deleteRecursively()
                        error("The model archive did not contain ${missing.joinToString()}. Found: $found")
                    }
                } catch (error: Throwable) {
                    dest.deleteRecursively()
                    throw error
                } finally {
                    archive.delete()
                    _progress.value = null
                }
            }
        }
    }

    private fun downloadArchive(model: AsrModel, dest: File) {
        var current = model.archiveUrl
        for (hop in 0 until 8) {
            val connection = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "glasses-hfp-transcribe")
                setRequestProperty("Accept", "application/octet-stream")
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: error("Download redirect had no Location header")
                connection.disconnect()
                current = if (location.startsWith("http")) {
                    location
                } else {
                    URI(current).resolve(location).toString()
                }
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                error("Could not download ${model.title} (HTTP $code)")
            }
            val total = connection.contentLengthLong.takeIf { it > 0L }
            val tmp = File(dest.parentFile, dest.name + ".part")
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        val fraction = if (total != null) {
                            (readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                        _progress.value = DownloadProgress(
                            modelId = model.id,
                            label = "Downloading ${model.title}",
                            fraction = fraction,
                        )
                    }
                }
            }
            connection.disconnect()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return
        }
        error("Too many redirects downloading ${model.title}")
    }

    private fun extractRequired(archive: File, dest: File, wanted: Set<String>) {
        dest.deleteRecursively()
        dest.mkdirs()
        BZip2CompressorInputStream(archive.inputStream()).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val name = File(entry.name).name
                    if (!entry.isDirectory && name in wanted) {
                        val out = File(dest, name)
                        check(out.canonicalFile.parentFile == dest.canonicalFile) {
                            "Refusing to extract ${entry.name}"
                        }
                        out.outputStream().use { output -> tar.copyTo(output) }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }
}
