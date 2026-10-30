package com.safi.teledrive

import android.app.Activity
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

class MainActivity : Activity(), TelegramSessionManager.Listener {
    private lateinit var status: TextView
    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var password: EditText
    private lateinit var qr: ImageView
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
            text = "Show QR login"
            setOnClickListener { sessions.startQrLogin() }
        })
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
