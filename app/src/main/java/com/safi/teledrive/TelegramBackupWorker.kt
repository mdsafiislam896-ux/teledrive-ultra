package com.safi.teledrive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager entry point for scheduled backups.
 * The actual upload queue must be connected to the authenticated TDLib session
 * and user-selected SAF URIs before enabling automatic backup in the UI.
 */
class TelegramBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Do not report success until the TDLib upload queue confirms completion.
        return Result.retry()
    }
}
