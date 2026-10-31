package com.safi.teledrive

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
    private val uploadPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri -> uploadSelectedFile(uri, uploadCategory) }
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
                    if (url.startsWith(ASSET_URL)) return false
                    trustedPageLoaded = false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    trustedPageLoaded = url.startsWith(ASSET_URL)
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
        val payload = JSONObject.quote(value.toString())
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
                        dispatchToPage("onNativeUploadComplete", "$safeName|$sizeBytes|$category")
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
        if (message.startsWith("Telegram error:") || message.startsWith("TDLib error:")) showBridgeError(message)
    }

    override fun onQrLink(link: String) = dispatchToPage("onQrLink", link)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private companion object { const val ASSET_URL = "file:///android_asset/" }
}
