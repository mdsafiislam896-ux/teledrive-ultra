package com.safi.teledrive

import android.content.Context
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Range-aware HTTP(S) downloader. Uses parallel byte ranges only when the
 * origin advertises byte ranges and a known content length; otherwise streams
 * the response as one connection. Output is placed in app-specific Downloads.
 */
class HttpDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val source = inputData.getString(KEY_URL)?.trim().orEmpty()
        val requestedThreads = inputData.getInt(KEY_THREADS, 8).coerceIn(1, 16)
        if (!source.startsWith("https://") && !source.startsWith("http://")) return@withContext Result.failure()
        try {
            val url = URL(source)
            val probe = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            val length = probe.contentLengthLong
            val ranges = probe.getHeaderField("Accept-Ranges")?.equals("bytes", true) == true
            val name = (probe.getHeaderField("Content-Disposition")?.substringAfter("filename=")
                ?.trim('"', ' ', ';') ?: url.path.substringAfterLast('/').ifBlank { "download.bin" })
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            probe.disconnect()

            val dir = File(applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: applicationContext.filesDir, "downloads").apply { mkdirs() }
            val output = File(dir, name)
            if (length > 0 && ranges && requestedThreads > 1) {
                RandomAccessFile(output, "rw").use { it.setLength(length) }
                val count = requestedThreads.coerceAtMost(length.toInt().coerceAtLeast(1))
                val completed = AtomicLong(0)
                coroutineScope {
                    (0 until count).map { index ->
                        async(Dispatchers.IO) {
                            val start = length * index / count
                            val end = length * (index + 1) / count - 1
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                setRequestProperty("Range", "bytes=$start-$end")
                                connectTimeout = 20000
                                readTimeout = 30000
                                instanceFollowRedirects = true
                            }
                            try {
                                if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                                    throw IllegalStateException("Server rejected byte-range request")
                                }
                                conn.inputStream.use { input ->
                                    RandomAccessFile(output, "rw").use { file ->
                                        file.seek(start)
                                        val buffer = ByteArray(128 * 1024)
                                        while (true) {
                                            if (isStopped) throw InterruptedException("Download cancelled")
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            file.write(buffer, 0, n)
                                            completed.addAndGet(n.toLong())
                                        }
                                    }
                                }
                            } finally { conn.disconnect() }
                        }
                    }.awaitAll()
                }
            } else {
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                try {
                    conn.inputStream.use { input -> output.outputStream().use { input.copyTo(it, 128 * 1024) } }
                } finally { conn.disconnect() }
            }
            Result.success(workDataOf(KEY_OUTPUT to output.absolutePath, KEY_NAME to name))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    companion object {
        const val KEY_URL = "http_url"
        const val KEY_THREADS = "thread_count"
        const val KEY_OUTPUT = "output_path"
        const val KEY_NAME = "file_name"
        const val KEY_ERROR = "error"
    }
}
