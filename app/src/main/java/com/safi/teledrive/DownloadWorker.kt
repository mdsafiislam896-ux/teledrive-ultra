package com.safi.teledrive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Saves user-requested HTTP(S) downloads to the system Downloads collection. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val source = inputData.getString(KEY_URL).orEmpty()
        val name = safeName(inputData.getString(KEY_NAME).orEmpty())
        val mimeType = inputData.getString(KEY_MIME).orEmpty().ifBlank { "application/octet-stream" }
        if (!source.startsWith("https://") && !source.startsWith("http://")) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Only HTTP and HTTPS downloads are supported."))
        }

        var destination: Uri? = null
        try {
            val resolver = applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TeleDrive")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Unable to create the Downloads entry.")
            } else {
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TeleDrive")
                check(folder.exists() || folder.mkdirs()) { "Unable to create the Downloads folder." }
                Uri.fromFile(uniqueFile(folder, name))
            }

            val target = destination ?: error("Unable to create the Downloads entry.")
            val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                check(connection.responseCode in 200..299) { "Server returned HTTP ${connection.responseCode}." }
                val total = connection.contentLengthLong
                var downloaded = 0L
                connection.inputStream.use { input ->
                    val output = if (target.scheme == "content") resolver.openOutputStream(target, "w")
                    else File(target.path!!).outputStream()
                    output?.buffered()?.use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (isStopped) throw InterruptedException("Download cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            downloaded += count
                            setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to total))
                        }
                    } ?: error("Unable to write to Downloads.")
                }
            } finally {
                connection.disconnect()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            Result.success(workDataOf(KEY_OUTPUT to target.toString(), KEY_NAME to name))
        } catch (error: InterruptedException) {
            destination?.let { applicationContext.contentResolver.delete(it, null, null) }
            Result.failure(workDataOf(KEY_ERROR to "Download cancelled."))
        } catch (error: Exception) {
            destination?.let { applicationContext.contentResolver.delete(it, null, null) }
            if (runAttemptCount < MAX_RETRIES && !isStopped) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (error.message ?: "Download failed.")))
        }
    }

    private fun safeName(value: String): String =
        value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(150).ifBlank { "download.bin" }

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        var suffix = 1
        while (candidate.exists()) {
            val dot = name.lastIndexOf('.')
            candidate = File(folder, "${if (dot > 0) name.substring(0, dot) else name} ($suffix)${if (dot > 0) name.substring(dot) else ""}")
            suffix++
        }
        return candidate
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_NAME = "name"
        private const val KEY_MIME = "mime"
        private const val KEY_OUTPUT = "output"
        private const val KEY_DOWNLOADED = "downloaded"
        private const val KEY_TOTAL = "total"
        private const val KEY_ERROR = "error"
        private const val MAX_RETRIES = 3

        fun schedule(context: Context, url: String, fileName: String, mimeType: String = ""): String {
            require(url.startsWith("https://") || url.startsWith("http://")) { "Only HTTP and HTTPS downloads are supported." }
            val normalizedName = fileName.trim().ifBlank { "download.bin" }
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_URL to url, KEY_NAME to normalizedName, KEY_MIME to mimeType))
                .addTag("teledrive-download")
                .build()
            val hash = MessageDigest.getInstance("SHA-256").digest((url + normalizedName).toByteArray())
                .joinToString("") { "%02x".format(it) }.take(24)
            WorkManager.getInstance(context).enqueueUniqueWork("teledrive-download-$hash", ExistingWorkPolicy.KEEP, request)
            return request.id.toString()
        }
    }
}
