package com.safi.teledrive

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), TelegramSessionManager.Listener {
    private lateinit var status: TextView
    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var password: EditText
    private lateinit var qr: ImageView
    private lateinit var fileId: EditText
    private lateinit var sessions: TelegramSessionManager
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadDetails: TextView
    private var activeDownload: java.util.UUID? = null

    private val bg = Color.rgb(5, 20, 18)
    private val surface = Color.rgb(12, 38, 32)
    private val green = Color.rgb(0, 220, 125)
    private val muted = Color.rgb(160, 187, 177)
    private val white = Color.rgb(242, 250, 246)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(3, 15, 13)
        window.navigationBarColor = Color.rgb(3, 15, 13)
        window.decorView.systemUiVisibility = 0
        sessions = TelegramSessionManager(this, this)
        buildInterface()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(28))
            addView(root)
        }
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = gradient(Color.rgb(4, 48, 35), Color.rgb(7, 25, 23), 0)
        }
        val brandRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = TextView(this).apply {
            text = "N"
            textSize = 26f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(0, 245, 150), Color.rgb(0, 110, 85))).apply { cornerRadius = dp(18).toFloat() }
        }
        brandRow.addView(logo, LinearLayout.LayoutParams(dp(52), dp(52)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brand.addView(label("NexaCloud", 25f, white, true))
        brand.addView(label("TELEGRAM DRIVE  •  SMART DOWNLOADS", 10f, muted, true))
        brandRow.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val accountButton = pillButton("ACCOUNTS") { showAccounts() }
        brandRow.addView(accountButton)
        header.addView(brandRow)
        header.addView(label("Your cloud. Your files. One place.", 15f, muted, false).apply {
            setPadding(0, dp(20), 0, dp(5))
        })
        status = label("●  Telegram not connected", 13f, Color.rgb(255, 194, 91), true)
        header.addView(status)
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), 0)
        }
        root.addView(content)

        sectionTitle(content, "TELEGRAM ACCOUNT", "Connect and manage your Telegram sessions")
        val auth = card(content)
        phone = edit("Phone number with country code  •  +8801…", 3)
        auth.addView(phone)
        auth.addView(actionButton("＋  CONNECT / ADD ACCOUNT") {
            safeAction { sessions.startPhoneLogin(phone.text.toString().trim()) }
        })
        code = edit("Telegram verification code", 2)
        auth.addView(code)
        auth.addView(actionButton("VERIFY LOGIN CODE") {
            safeAction { sessions.submitCode(code.text.toString().trim()) }
        })
        password = edit("Telegram 2-step verification password", 1)
        auth.addView(password)
        auth.addView(actionButton("VERIFY 2-STEP PASSWORD") {
            safeAction { sessions.submitPassword(password.text.toString()) }
        })
        auth.addView(actionButton("▦  SIGN IN WITH QR CODE", secondary = true) {
            safeAction { sessions.startQrLogin() }
        })
        qr = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        auth.addView(qr, LinearLayout.LayoutParams(-1, dp(220)))

        sectionTitle(content, "TELEGRAM DRIVE", "Send files to your Saved Messages")
        val drive = card(content)
        drive.addView(actionButton("↑  UPLOAD FILE TO SAVED MESSAGES") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }
            startActivityForResult(intent, 4101)
        })
        fileId = edit("Telegram file ID to download", 2)
        drive.addView(fileId)
        drive.addView(actionButton("↓  DOWNLOAD TELEGRAM FILE") {
            val id = fileId.text.toString().toIntOrNull()
            if (id == null) status.text = "Enter a valid Telegram file ID"
            else safeAction { status.text = "Requesting Telegram file…"; sessions.downloadFile(id, transferCallback()) }
        })

        sectionTitle(content, "AUTOMATIC CLOUD BACKUP", "Keep a selected folder backed up every day")
        val backup = card(content)
        backup.addView(actionButton("▣  CHOOSE BACKUP FOLDER") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, 4102)
        })
        backup.addView(actionButton("CANCEL SCHEDULED BACKUP", secondary = true) {
            TelegramBackupScheduler.cancel(this)
            status.text = "Automatic backup schedule cancelled"
        })

        sectionTitle(content, "IDM-STYLE DOWNLOAD MANAGER", "Accelerated HTTP / HTTPS downloads")
        val downloader = card(content)
        val httpUrl = edit("Paste direct HTTP / HTTPS download URL", 17)
        downloader.addView(httpUrl)
        val threadCount = edit("Connections (1–16)", 2).apply { setText("8") }
        downloader.addView(threadCount)
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(green)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(35, 65, 57))
        }
        downloader.addView(downloadProgress, LinearLayout.LayoutParams(-1, dp(8)).apply {
            topMargin = dp(14); bottomMargin = dp(8)
        })
        downloadDetails = label("Ready for your next download", 12f, muted, false)
        downloader.addView(downloadDetails)
        downloader.addView(actionButton("⚡  START FAST DOWNLOAD") {
            val link = httpUrl.text.toString().trim()
            val count = threadCount.text.toString().toIntOrNull() ?: 8
            if (!link.startsWith("https://") && !link.startsWith("http://")) {
                downloadDetails.text = "Enter a valid direct HTTP/HTTPS URL"
            } else {
                val request = OneTimeWorkRequestBuilder<HttpDownloadWorker>()
                    .setInputData(workDataOf(HttpDownloadWorker.KEY_URL to link,
                        HttpDownloadWorker.KEY_THREADS to count.coerceIn(1, 16)))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .build()
                activeDownload = request.id
                WorkManager.getInstance(this).enqueue(request)
                observeDownload(request.id)
            }
        })
        downloader.addView(actionButton("Ⅱ  CANCEL CURRENT DOWNLOAD", secondary = true) {
            activeDownload?.let { WorkManager.getInstance(this).cancelWorkById(it) }
            downloadDetails.text = "Download cancellation requested"
        })
        content.addView(label("Files are saved in the app's Downloads folder.", 11f, muted, false).apply {
            setPadding(dp(4), dp(8), dp(4), 0)
        })
        content.addView(label("NexaCloud  •  Private by design", 11f, muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(12))
        })
    }

    private fun observeDownload(id: java.util.UUID) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info == null) return@observe
            val percent = info.progress.getInt(HttpDownloadWorker.KEY_PERCENT, -1)
            val done = info.progress.getLong(HttpDownloadWorker.KEY_DOWNLOADED, 0L)
            val total = info.progress.getLong(HttpDownloadWorker.KEY_TOTAL, 0L)
            if (percent >= 0) downloadProgress.progress = percent
            downloadDetails.text = when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    downloadProgress.progress = 100
                    "✓ Saved: ${info.outputData.getString(HttpDownloadWorker.KEY_NAME) ?: "download"}"
                }
                WorkInfo.State.FAILED -> "Download failed: ${info.outputData.getString(HttpDownloadWorker.KEY_ERROR) ?: "Unknown error"}"
                WorkInfo.State.CANCELLED -> "Download cancelled"
                WorkInfo.State.RUNNING -> "Downloading  ${if (percent >= 0) "$percent%" else "…"}  •  ${formatBytes(done)} / ${formatBytes(total)}"
                WorkInfo.State.ENQUEUED -> "Download queued"
                else -> info.state.name
            }
        }
    }

    private fun showAccounts() {
        val ids = sessions.savedAccountIds()
        if (ids.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Telegram accounts")
                .setMessage("No connected account is saved yet. Connect an account first.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Choose active account")
            .setItems(ids.mapIndexed { i, id -> "Account ${i + 1}  •  ${id.take(8)}" }.toTypedArray()) { _, which ->
                safeAction { sessions.switchAccount(ids[which]) }
            }.setNegativeButton("Close", null).show()
    }

    private fun sectionTitle(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(label(title, 13f, green, true).apply {
            setPadding(dp(3), dp(22), 0, dp(4))
        })
        parent.addView(label(subtitle, 11f, muted, false).apply {
            setPadding(dp(3), 0, 0, dp(10))
        })
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(surface); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(25, 70, 57))
            }
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        return box
    }

    private fun edit(hintText: String, type: Int): EditText = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setTextColor(white)
        setHintTextColor(Color.rgb(115, 146, 134))
        inputType = type
        setSingleLine(true)
        setPadding(dp(13), dp(12), dp(13), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(6, 27, 23)); cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(34, 77, 63))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    private fun actionButton(textValue: String, secondary: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            textSize = 12f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (secondary) green else Color.rgb(3, 28, 20))
            background = if (secondary) outlineButton() else gradient(Color.rgb(0, 235, 139), Color.rgb(0, 165, 105), dp(13))
            stateListAnimator = null
            minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
            setOnClickListener { action() }
        }

    private fun pillButton(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue; textSize = 10f; isAllCaps = false
        setTextColor(green); background = outlineButton()
        setPadding(dp(8), 0, dp(8), 0); minHeight = dp(38)
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun gradient(start: Int, end: Int, radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius.toFloat()
        }

    private fun outlineButton() = GradientDrawable().apply {
        setColor(Color.rgb(8, 44, 35)); cornerRadius = dp(13).toFloat()
        setStroke(dp(1), Color.rgb(0, 145, 96))
    }

    private fun safeAction(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            status.text = e.message ?: "Something went wrong"
        }
    }

    private fun transferCallback() = object : TelegramSessionManager.TransferCallback {
        override fun onSuccess(message: String) { runOnUiThread { status.text = message } }
        override fun onError(message: String) { runOnUiThread { status.text = "Transfer error: $message" } }
    }

    @Deprecated("Deprecated API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 4102) {
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                TelegramBackupScheduler.schedule(this, uri, 24L)
                status.text = "Daily cloud backup scheduled (Android WorkManager)"
            } catch (e: Exception) { status.text = "Could not schedule backup: ${e.message}" }
            return
        }
        if (requestCode != 4101) return
        status.text = "Preparing selected file…"
        Thread {
            try {
                val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else "upload.bin"
                } ?: "upload.bin"
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val local = java.io.File(cacheDir, "upload_${System.currentTimeMillis()}_$safeName")
                contentResolver.openInputStream(uri)?.use { input -> local.outputStream().use { output -> input.copyTo(output) } }
                    ?: throw IllegalStateException("Cannot open selected file")
                runOnUiThread { status.text = "Uploading $name…" }
                sessions.uploadToSavedMessages(local.absolutePath, name, transferCallback())
            } catch (e: Exception) { runOnUiThread { status.text = "File preparation failed: ${e.message}" } }
        }.start()
    }

    override fun onStatus(message: String) { runOnUiThread { if (::status.isInitialized) status.text = message } }

    override fun onQrLink(link: String) {
        runOnUiThread {
            if (!::qr.isInitialized) return@runOnUiThread
            try {
                val matrix = com.google.zxing.MultiFormatWriter().encode(link,
                    com.google.zxing.BarcodeFormat.QR_CODE, 640, 640)
                val bitmap = android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888)
                for (x in 0 until 640) for (y in 0 until 640) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
                qr.setImageBitmap(bitmap); qr.visibility = View.VISIBLE
            } catch (e: Exception) { status.text = "QR render failed: ${e.message}" }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble(); var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
