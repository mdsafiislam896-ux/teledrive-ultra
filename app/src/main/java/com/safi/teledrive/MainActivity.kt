package com.safi.teledrive

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity(), TelegramSessionManager.Listener {
    private lateinit var webView: WebView
    private lateinit var sessions: TelegramSessionManager
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            fileUploadCallback?.onReceiveValue(results)
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OLED Luxury Dark styling matching Figma theme (#0C0E12)
        window.statusBarColor = Color.rgb(12, 14, 18)
        window.navigationBarColor = Color.rgb(17, 20, 26)

        sessions = TelegramSessionManager(this, this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(12, 14, 18))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
            }

            addJavascriptInterface(WebAppBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    syncNativeAuthState()
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
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    try {
                        filePickerLauncher.launch(intent)
                    } catch (e: Exception) {
                        fileUploadCallback = null
                        return false
                    }
                    return true
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun syncNativeAuthState() {
        val active = sessions.hasActiveSession()
        val js = "if(window.onNativeAuthSynced) window.onNativeAuthSynced($active);"
        webView.evaluateJavascript(js, null)
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun startLogin(phone: String) {
            runOnUiThread {
                sessions.startPhoneAuth(phone)
            }
        }

        @JavascriptInterface
        fun submitCode(code: String) {
            runOnUiThread {
                sessions.submitCode(code)
            }
        }

        @JavascriptInterface
        fun submitPassword(pwd: String) {
            runOnUiThread {
                sessions.submitPassword(pwd)
            }
        }

        @JavascriptInterface
        fun downloadFileToPublic(url: String, fileName: String, mimeType: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Saving $fileName to Downloads/TeleDrive...", Toast.LENGTH_SHORT).show()
                DownloadWorker.schedule(this@MainActivity, url, fileName)
            }
        }

        @JavascriptInterface
        fun removeAccount() {
            runOnUiThread {
                sessions.removeAccount()
                Toast.makeText(this@MainActivity, "Account removed and KeyStore purged", Toast.LENGTH_SHORT).show()
                syncNativeAuthState()
            }
        }
    }

    override fun onAuthStateChanged(state: String) {
        runOnUiThread {
            webView.evaluateJavascript("if(window.onAuthStateChanged) window.onAuthStateChanged('$state');", null)
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
