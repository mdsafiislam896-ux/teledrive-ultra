package com.safi.teledrive

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import java.io.File
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Periodically mirrors changed files from a user-selected SAF folder to the
 * authenticated Telegram account's Saved Messages chat.
 */
class TelegramBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tree = inputData.getString(KEY_TREE_URI)?.let(Uri::parse)
            ?: return Result.failure()
        val manager = try {
            TelegramSessionManager(applicationContext, object : TelegramSessionManager.Listener {
                override fun onStatus(message: String) { setProgressAsync(Data.Builder().putString("status", message).build()) }
                override fun onQrLink(link: String) { }
            })
        } catch (e: Exception) {
            return Result.retry()
        }

        if (!manager.awaitActiveReady()) return Result.retry()
        val root = DocumentFile.fromTreeUri(applicationContext, tree) ?: return Result.failure()
        val prefs = applicationContext.getSharedPreferences("teledrive_backup_index", Context.MODE_PRIVATE)
        var uploaded = 0
        var skipped = 0
        return try {
            for (file in root.walkFiles()) {
                if (isStopped) return Result.retry()
                if (uploaded >= MAX_FILES_PER_RUN) break
                val name = file.name ?: "backup-file"
                val fingerprint = "${file.length()}:${file.lastModified()}"
                val key = Uri.encode(file.uri.toString())
                if (prefs.getString(key, null) == fingerprint) {
                    skipped++
                    continue
                }
                val local = File(applicationContext.cacheDir, "backup_${System.currentTimeMillis()}_${name.safeFileName()}")
                try {
                    applicationContext.contentResolver.openInputStream(file.uri)?.use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Unable to read $name")
                    withTimeout(4 * 60 * 1000L) {
                        manager.uploadAwait(local, name)
                    }
                    prefs.edit().putString(key, fingerprint).apply()
                    uploaded++
                    setProgress(Data.Builder().putInt("uploaded", uploaded).putInt("skipped", skipped).build())
                } finally {
                    local.delete()
                }
            }
            Result.success(Data.Builder().putInt("uploaded", uploaded).putInt("skipped", skipped).build())
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun TelegramSessionManager.uploadAwait(file: File, caption: String) =
        suspendCancellableCoroutine<Unit> { continuation ->
            uploadToSavedMessages(file.absolutePath, caption, object : TelegramSessionManager.TransferCallback {
                override fun onSuccess(message: String) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onError(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }
            })
        }

    private fun DocumentFile.walkFiles(): Sequence<DocumentFile> = sequence {
        if (isFile) {
            yield(this@walkFiles)
        } else if (isDirectory) {
            listFiles().forEach { child ->
                if (child.isDirectory) yieldAll(child.walkFiles())
                else if (child.isFile) yield(child)
            }
        }
    }

    private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)

    companion object {
        const val KEY_TREE_URI = "backup_tree_uri"
        const val WORK_NAME = "teledrive_periodic_cloud_backup"
        private const val MAX_FILES_PER_RUN = 100
    }
}
