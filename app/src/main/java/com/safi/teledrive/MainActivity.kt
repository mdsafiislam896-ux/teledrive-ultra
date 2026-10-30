package com.safi.teledrive

import androidx.activity.ComponentActivity
import android.content.Intent
import java.io.File
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class MainActivity : ComponentActivity(), TelegramSessionManager.Listener {
    private lateinit var status: TextView
    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var password: EditText
    private lateinit var qr: ImageView
    private lateinit var fileId: EditText
    private lateinit var sessions: TelegramSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessions = TelegramSessionManager(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "TeleDrive Ultra"
            textSize = 28f
            setTextColor(Color.rgb(20, 105, 75))
        })
        status = TextView(this).apply { text = "Telegram TDLib • Not connected"; textSize = 15f }
        root.addView(status)
        phone = EditText(this).apply { hint = "Phone number, e.g. +8801…"; inputType = 3 }
        root.addView(phone)
        root.addView(Button(this).apply {
            text = "Start / Add Telegram account"
            setOnClickListener { sessions.startPhoneLogin(phone.text.toString()) }
        })
        code = EditText(this).apply { hint = "Telegram login code"; inputType = 2 }
        root.addView(code)
        root.addView(Button(this).apply {
            text = "Submit code"
            setOnClickListener { sessions.submitCode(code.text.toString()) }
        })
        password = EditText(this).apply { hint = "Telegram 2-step verification password" }
        root.addView(password)
        root.addView(Button(this).apply {
            text = "Submit 2FA password"
            setOnClickListener { sessions.submitPassword(password.text.toString()) }
        })
        qr = ImageView(this)
        root.addView(qr)
        root.addView(Button(this).apply {
            text = "Start QR login"
            setOnClickListener { sessions.startQrLogin() }
        })
        root.addView(Button(this).apply {
            text = "Choose backup folder (automatic daily cloud backup)"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, 4102)
            }
        })
        root.addView(Button(this).apply {
            text = "Cancel automatic cloud backup"
            setOnClickListener {
                TelegramBackupScheduler.cancel(this@MainActivity)
                status.text = "Automatic backup cancelled"
            }
        })
        root.addView(Button(this).apply {
            text = "Choose file and upload to Saved Messages"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, 4101)
            }
        })
        fileId = EditText(this).apply {
            hint = "Telegram file ID to download"
            inputType = 2
        }
        root.addView(fileId)
        root.addView(Button(this).apply {
            text = "Download Telegram file"
            setOnClickListener {
                val id = fileId.text.toString().toIntOrNull()
                if (id == null) {
                    status.text = "Enter a valid Telegram file ID"
                } else {
                    status.text = "Downloading file…"
                    sessions.downloadFile(id, transferCallback())
                }
            }
        })
        root.addView(TextView(this).apply {
            text = "IDM-style HTTP Download Manager"
            textSize = 20f
            setPadding(0, 28, 0, 8)
        })
        val httpUrl = EditText(this).apply {
            hint = "Paste direct HTTP/HTTPS download link"
            inputType =  uriInputType()
        }
        root.addView(httpUrl)
        val threadCount = EditText(this).apply {
            hint = "Connections (1–16, default 8)"
            setText("8")
            inputType = 2
        }
        root.addView(threadCount)
        root.addView(Button(this).apply {
            text = "Start fast download"
            setOnClickListener {
                val link = httpUrl.text.toString().trim()
                val count = threadCount.text.toString().toIntOrNull() ?: 8
                if (!link.startsWith("https://") && !link.startsWith("http://")) {
                    status.text = "Enter a valid direct HTTP/HTTPS link"
                } else {
                    val request = OneTimeWorkRequestBuilder<HttpDownloadWorker>()
                        .setInputData(workDataOf(
                            HttpDownloadWorker.KEY_URL to link,
                            HttpDownloadWorker.KEY_THREADS to count.coerceIn(1, 16)
                        )).build()
                    WorkManager.getInstance(this@MainActivity).enqueue(request)
                    status.text = "Download queued. Parallel ranges will be used if the server supports them."
                    WorkManager.getInstance(this@MainActivity).getWorkInfoByIdLiveData(request.id)
                        .observe(this@MainActivity) { info ->
                            if (info != null && info.state.isFinished) {
                                status.text = if (info.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                                    "Download saved: ${info.outputData.getString(HttpDownloadWorker.KEY_OUTPUT)}"
                                } else {
                                    "Download failed: ${info.outputData.getString(HttpDownloadWorker.KEY_ERROR) ?: info.state}"
                                }
                            } else if (info != null) {
                                status.text = "HTTP download: ${info.state}"
                            }
                        }
                }
            }
        })
    }

    private fun uriInputType(): Int = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI

    private fun transferCallback() = object : TelegramSessionManager.TransferCallback {
        override fun onSuccess(message: String) { runOnUiThread { status.text = message } }
        override fun onError(message: String) { runOnUiThread { status.text = "Transfer error: $message" } }
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 4102) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                TelegramBackupScheduler.schedule(this, uri, 24L)
                status.text = "Daily cloud backup scheduled for the selected folder"
            } catch (e: Exception) {
                status.text = "Could not schedule backup: ${e.message}"
            }
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
                val local = File(cacheDir, "upload_${System.currentTimeMillis()}_$safeName")
                contentResolver.openInputStream(uri)?.use { input ->
                    local.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open selected file")
                runOnUiThread { status.text = "Uploading $name…" }
                sessions.uploadToSavedMessages(local.absolutePath, name, transferCallback())
            } catch (e: Exception) {
                runOnUiThread { status.text = "File preparation failed: ${e.message}" }
            }
        }.start()
    }

    override fun onStatus(message: String) { runOnUiThread { status.text = message } }
    override fun onQrLink(link: String) {
        runOnUiThread {
            try {
                val matrix: BitMatrix = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 640, 640)
                val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
                for (x in 0 until 640) for (y in 0 until 640) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
                qr.setImageBitmap(bitmap)
            } catch (e: Exception) { status.text = "QR render failed: ${e.message}" }
        }
    }
}
