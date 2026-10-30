package com.safi.teledrive

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object TelegramBackupScheduler {
    fun schedule(context: Context, treeUri: Uri, intervalHours: Long = 24L) {
        require(intervalHours >= 1L) { "Backup interval must be at least one hour." }
        val request = PeriodicWorkRequestBuilder<TelegramBackupWorker>(
            intervalHours.coerceAtLeast(1L), TimeUnit.HOURS
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).setInputData(
            workDataOf(TelegramBackupWorker.KEY_TREE_URI to treeUri.toString())
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TelegramBackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TelegramBackupWorker.WORK_NAME)
    }
}
