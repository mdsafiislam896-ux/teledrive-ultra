package com.safi.teledrive

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Hosts the bundled UI and exposes a small, trusted bridge to TDLib. */
class MainActivity : ComponentActivity(), TelegramSessionManager.Listener {
    private lateinit var webView: WebView
    private lateinit var sessions: TelegramSessionManager
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    @Volatile private var trustedPageLoaded = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback ?: return@registerForActivityResult
        callback.onReceiveValue(
            if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else null
        )
        fileUploadCallback = null
    }

    private var uploadCategory = "files"
    private var backupIntervalHours = 24L
    private val uploadPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri -> uploadSelectedFile(uri, uploadCategory) }
    }

    private val backupFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            TelegramBackupScheduler.schedule(applicationContext, uri, backupIntervalHours)
            dispatchToPage("onNativeBackupScheduled", backupIntervalHours)
        } catch (error: Exception) {
            showBridgeError(error.message ?: "Unable to save the backup folder permission.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 14, 18)
        window.navigationBarColor = Color.rgb(17, 20, 26)

        sessions = TelegramSessionManager(applicationContext, this)
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(12, 14, 18))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = true // Needed only for user-selected content:// URIs.
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
            }
            addJavascriptInterface(WebAppBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    if (url.startsWith(ASSET_ROOT)) return false
                    trustedPageLoaded = false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    trustedPageLoaded = url.startsWith(ASSET_ROOT)
                    if (trustedPageLoaded) syncNativeAuthState()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    return try {
                        filePickerLauncher.launch(
                            fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                type = "*/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                        )
                        true
                    } catch (_: Exception) {
                        fileUploadCallback = null
                        false
                    }
                }
            }
        }

        setContentView(webView)
        webView.loadUrl(ASSET_URL)
    }

    override fun onDestroy() {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun syncNativeAuthState() = dispatchToPage("onNativeAuthSynced", sessions.hasActiveSession())

    private fun dispatchToPage(callback: String, value: Any) {
        if (!trustedPageLoaded || !::webView.isInitialized) return
        val payload = when (value) {
            is JSONObject, is JSONArray -> value.toString()
            is Boolean, is Number -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
        webView.post {
            if (trustedPageLoaded) webView.evaluateJavascript("window.$callback && window.$callback($payload);", null)
        }
    }

    private fun showBridgeError(message: String) {
        dispatchToPage("onNativeError", message)
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun uploadSelectedFile(uri: Uri, category: String) {
        val resolver = contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() } ?: "upload-${System.currentTimeMillis()}"
        val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
        val cacheFile = File(cacheDir, "upload-${System.currentTimeMillis()}-$safeName")
        try {
            resolver.openInputStream(uri)?.use { input -> cacheFile.outputStream().use(input::copyTo) }
                ?: error("Unable to read the selected file.")
            val sizeBytes = cacheFile.length()
            dispatchToPage("onNativeUploadStarted", "$safeName|$sizeBytes|$category")
            sessions.uploadToSavedMessages(cacheFile.absolutePath, safeName,
                object : TelegramSessionManager.TransferCallback {
                    override fun onSuccess(message: String) {
                        cacheFile.delete()
                        val messageId = Regex("message (\\d+)").find(message)?.groupValues?.getOrNull(1).orEmpty()
                        dispatchToPage("onNativeUploadComplete", "$safeName|$sizeBytes|$category|$messageId")
                        dispatchToPage("onNativeStatus", message)
                    }

                    override fun onError(message: String) {
                        cacheFile.delete()
                        showBridgeError(message)
                    }
                }
            )
        } catch (error: Exception) {
            cacheFile.delete()
            showBridgeError(error.message ?: "Unable to prepare the selected file.")
        }
    }

    private fun dispatchAccounts() {
        val accounts = JSONArray().apply {
            sessions.accountInfo().forEach { account ->
                put(JSONObject().apply {
                    put("id", account.id)
                    put("phone", account.phoneNumber)
                    put("ready", account.ready)
                    put("active", account.active)
                })
            }
        }
        dispatchToPage("onNativeAccounts", accounts)
    }

    private fun dispatchCloudFiles() {
        sessions.listSavedFiles(callback = object : TelegramSessionManager.CloudFilesCallback {
            override fun onSuccess(files: List<TelegramSessionManager.CloudFile>) {
                val payload = JSONArray().apply {
                    files.forEach { file ->
                        put(JSONObject().apply {
                            put("messageId", file.messageId)
                            put("fileId", file.fileId)
                            put("name", file.name)
                            put("mimeType", file.mimeType)
                            put("size", file.size)
                            put("date", file.date)
                        })
                    }
                }
                dispatchToPage("onNativeCloudFiles", payload)
            }

            override fun onError(message: String) = showBridgeError(message)
        })
    }

    private fun exportToDownloads(sourcePath: String, requestedName: String, mimeType: String) {
        val source = File(sourcePath)
        if (!source.isFile) {
            showBridgeError("Telegram did not return a downloadable file.")
            return
        }
        try {
            val name = requestedName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "download" }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NexaCloud")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
            val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create the Downloads entry.")
            try {
                source.inputStream().use { input ->
                    resolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                        ?: error("Unable to write to Downloads.")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
                dispatchToPage("onNativeCloudDownloadComplete", name)
            } catch (error: Exception) {
                resolver.delete(destination, null, null)
                throw error
            }
        } catch (error: Exception) {
            showBridgeError(error.message ?: "Unable to save the Telegram file to Downloads.")
        }
    }

    inner class WebAppBridge {
        private fun allowCall(): Boolean {
            if (trustedPageLoaded) return true
            showBridgeError("This action is available only in the TeleDrive app.")
            return false
        }

        @JavascriptInterface
        fun startLogin(phone: String?) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            runCatching { sessions.startPhoneAuth(phone.orEmpty()) }
                .onFailure { showBridgeError(it.message ?: "Unable to start Telegram login.") }
        }

        @JavascriptInterface
        fun startQrLogin() = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            runCatching { sessions.startQrLogin() }
                .onFailure { showBridgeError(it.message ?: "Unable to start QR login.") }
        }

        @JavascriptInterface
        fun pickAndUpload(category: String?) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            if (!sessions.hasActiveSession()) {
                showBridgeError("Connect a Telegram account before uploading files.")
                return@runOnUiThread
            }
            uploadCategory = if (category == "photos") "photos" else "files"
            uploadPickerLauncher.launch(arrayOf("*/*"))
        }

        @JavascriptInterface
        fun refreshAccounts() = runOnUiThread { if (allowCall()) dispatchAccounts() }

        @JavascriptInterface
        fun switchAccount(accountId: String?) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            runCatching { sessions.switchAccount(accountId.orEmpty()); dispatchAccounts(); dispatchCloudFiles() }
                .onFailure { showBridgeError(it.message ?: "Unable to switch Telegram account.") }
        }

        @JavascriptInterface
        fun refreshCloudFiles() = runOnUiThread { if (allowCall()) dispatchCloudFiles() }

        @JavascriptInterface
        fun downloadCloudFile(fileId: Int, fileName: String?, mimeType: String?) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            sessions.downloadFile(fileId, object : TelegramSessionManager.TransferCallback {
                override fun onSuccess(message: String) = exportToDownloads(message, fileName.orEmpty(), mimeType.orEmpty())
                override fun onError(message: String) = showBridgeError(message)
            })
        }

        @JavascriptInterface
        fun scheduleBackup(intervalHours: Int) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            backupIntervalHours = intervalHours.toLong().coerceIn(1L, 24L * 30L)
            backupFolderLauncher.launch(null)
        }

        @JavascriptInterface
        fun submitCode(code: String?) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            runCatching { sessions.submitCode(code.orEmpty()) }
                .onFailure { showBridgeError(it.message ?: "Unable to submit the Telegram code.") }
        }

        @JavascriptInterface
        fun submitPassword(password: String?) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            runCatching { sessions.submitPassword(password.orEmpty()) }
                .onFailure { showBridgeError(it.message ?: "Unable to submit the 2-step verification password.") }
        }

        @JavascriptInterface
        fun downloadFileToPublic(url: String?, fileName: String?, mimeType: String?) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            runCatching {
                DownloadWorker.schedule(this@MainActivity, url.orEmpty(), fileName.orEmpty(), mimeType.orEmpty())
            }.onSuccess {
                Toast.makeText(this@MainActivity, "Download added to the queue.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                showBridgeError(it.message ?: "Unable to queue this download.")
            }
        }

        @JavascriptInterface
        fun removeAccount() = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            sessions.removeAccount()
            syncNativeAuthState()
        }

        @JavascriptInterface
        fun deleteSavedMessage(messageId: String?) = runOnUiThread {
            if (!allowCall()) return@runOnUiThread
            val id = messageId?.toLongOrNull()
            if (id == null) {
                showBridgeError("This file has no Telegram message reference.")
                return@runOnUiThread
            }
            sessions.deleteSavedMessage(id, object : TelegramSessionManager.TransferCallback {
                override fun onSuccess(message: String) {
                    dispatchToPage("onNativeDeleteComplete", id)
                    dispatchToPage("onNativeStatus", message)
                }

                override fun onError(message: String) = showBridgeError(message)
            })
        }
    }

    override fun onStatus(message: String) {
        val state = when {
            message.contains("OTP required", ignoreCase = true) -> "code"
            message.contains("password required", ignoreCase = true) -> "password"
            message.contains("connected", ignoreCase = true) -> "ready"
            message.contains("QR", ignoreCase = true) -> "qr"
            else -> "status"
        }
        dispatchToPage("onAuthStateChanged", state)
        dispatchToPage("onNativeStatus", message)
        if (message.contains("connected", ignoreCase = true) || message.contains("removed", ignoreCase = true)) {
            dispatchAccounts()
        }
        if (message.startsWith("Telegram error:") || message.startsWith("TDLib error:")) showBridgeError(message)
    }

    override fun onQrLink(link: String) = dispatchToPage("onQrLink", link)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private companion object {
        const val ASSET_ROOT = "file:///android_asset/"
        const val ASSET_URL = "file:///android_asset/index.html"
    }
}
