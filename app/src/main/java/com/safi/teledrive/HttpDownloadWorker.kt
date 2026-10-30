package com.safi.teledrive

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Range-aware downloader. Parallel segments are used only after the origin
 * proves range support. Progress is persisted through WorkManager; incomplete
 * files are kept under a temporary name and never presented as completed.
 */
class HttpDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val source = inputData.getString(KEY_URL)?.trim().orEmpty()
        val threads = inputData.getInt(KEY_THREADS, 8).coerceIn(1, 16)
        if (!source.startsWith("https://") && !source.startsWith("http://")) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Only HTTP and HTTPS links are supported."))
        }

        var temp: File? = null
        try {
            val url = URL(source)
            val metadata = probe(url)
            val name = safeName(metadata.fileName ?: url.path.substringAfterLast('/').ifBlank { "download.bin" })
            val dir = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: applicationContext.filesDir, "downloads").apply { mkdirs() }
            val output = uniqueFile(dir, name)
            temp = File(dir, ".$name.$id.part")
            temp.delete()

            val length = metadata.length
            val parallel = metadata.acceptsRanges && length > 1L && threads > 1
            if (parallel) {
                try {
                    downloadRanges(url, temp, length, threads)
                } catch (rangeError: Exception) {
                    if (isStopped) throw InterruptedException("Download cancelled")
                    temp.delete()
                    downloadSingle(url, temp, length)
                }
            } else {
                downloadSingle(url, temp, length)
            }
            if (isStopped) throw InterruptedException("Download cancelled")
            if (!temp.isFile || temp.length() == 0L) throw IllegalStateException("The server returned an empty file.")
            if (length > 0L && temp.length() != length) {
                throw IllegalStateException("Incomplete download: expected $length bytes, received ${temp.length()}.")
            }
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = false)
                temp.delete()
            }
            Result.success(workDataOf(KEY_OUTPUT to output.absolutePath, KEY_NAME to output.name, KEY_BYTES to output.length()))
        } catch (e: InterruptedException) {
            temp?.delete()
            Result.failure(workDataOf(KEY_ERROR to "Download cancelled."))
        } catch (e: Exception) {
            temp?.delete()
            if (runAttemptCount < MAX_RETRIES && !isStopped) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed.")))
        }
    }

    private data class Metadata(val length: Long, val acceptsRanges: Boolean, val fileName: String?)

    private fun probe(url: URL): Metadata {
        val head = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        try {
            val code = head.responseCode
            if (code in 200..299) {
                val length = head.getHeaderFieldLong("Content-Length", -1L)
                val ranges = head.getHeaderField("Accept-Ranges")?.equals("bytes", true) == true
                val name = parseFileName(head.getHeaderField("Content-Disposition"))
                if (length >= 0L) return Metadata(length, ranges, name)
            }
        } finally { head.disconnect() }

        // HEAD is frequently disabled. A one-byte GET establishes range support
        // and obtains the total size from Content-Range when available.
        val get = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=0-0")
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        try {
            val code = get.responseCode
            val contentRange = get.getHeaderField("Content-Range")
            val total = contentRange?.substringAfterLast('/')?.toLongOrNull() ?: get.contentLengthLong
            val supports = code == HttpURLConnection.HTTP_PARTIAL && contentRange?.startsWith("bytes 0-0/") == true
            return Metadata(total, supports, parseFileName(get.getHeaderField("Content-Disposition")))
        } finally {
            try { get.inputStream.close() } catch (_: Exception) { }
            get.disconnect()
        }
    }

    private suspend fun downloadRanges(url: URL, output: File, length: Long, requestedThreads: Int) {
        val count = minOf(requestedThreads.toLong(), length).toInt().coerceAtLeast(1)
        RandomAccessFile(output, "rw").use { it.setLength(length) }
        val completed = AtomicLong(0L)
        coroutineScope {
            (0 until count).map { index ->
                async(Dispatchers.IO) {
                    val start = length * index / count
                    val end = length * (index + 1) / count - 1L
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        setRequestProperty("Range", "bytes=$start-$end")
                        connectTimeout = 20000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                    }
                    try {
                        if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                            throw IllegalStateException("Range request rejected.")
                        }
                        val expectedPrefix = "bytes $start-$end/"
                        if (conn.getHeaderField("Content-Range")?.startsWith(expectedPrefix) != true) {
                            throw IllegalStateException("Invalid Content-Range response.")
                        }
                        conn.inputStream.use { input ->
                            RandomAccessFile(output, "rw").use { file ->
                                file.seek(start)
                                val buffer = ByteArray(128 * 1024)
                                var segmentBytes = 0L
                                while (true) {
                                    if (isStopped) throw InterruptedException("Download cancelled")
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    segmentBytes += n
                                    if (segmentBytes > end - start + 1L) throw IllegalStateException("Server sent excess range data.")
                                    file.write(buffer, 0, n)
                                    val total = completed.addAndGet(n.toLong())
                                    setProgress(workDataOf(KEY_DOWNLOADED to total, KEY_TOTAL to length, KEY_PERCENT to (total * 100L / length).toInt()))
                                }
                                if (segmentBytes != end - start + 1L) throw IllegalStateException("Server returned a short range.")
                            }
                        }
                    } finally { conn.disconnect() }
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadSingle(url: URL, output: File, expectedLength: Long) {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.getHeaderFieldLong("Content-Length", expectedLength)
            var downloaded = 0L
            conn.inputStream.use { input ->
                output.outputStream().buffered().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (isStopped) throw InterruptedException("Download cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        setProgress(workDataOf(
                            KEY_DOWNLOADED to downloaded,
                            KEY_TOTAL to total,
                            KEY_PERCENT to if (total > 0L) (downloaded * 100L / total).toInt() else -1
                        ))
                    }
                }
            }
            if (expectedLength > 0L && downloaded != expectedLength) {
                throw IllegalStateException("Incomplete response: expected $expectedLength bytes, received $downloaded.")
            }
        } finally { conn.disconnect() }
    }

    private fun parseFileName(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val utf = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
        val plain = Regex("filename=\"?([^\";]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
        return (utf?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } } ?: plain)
    }

    private fun safeName(value: String): String =
        value.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.').take(150).ifBlank { "download.bin" }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            candidate = File(dir, "$stem ($index)$ext")
            index++
        }
        return candidate
    }

    companion object {
        const val KEY_URL = "http_url"
        const val KEY_THREADS = "thread_count"
        const val KEY_OUTPUT = "output_path"
        const val KEY_NAME = "file_name"
        const val KEY_ERROR = "error"
        const val KEY_DOWNLOADED = "downloaded_bytes"
        const val KEY_TOTAL = "total_bytes"
        const val KEY_PERCENT = "percent"
        const val KEY_BYTES = "file_bytes"
        private const val MAX_RETRIES = 3
    }
}
