package com.safi.teledrive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Each account gets an independent TDLib client, database directory and database key.
 * OTPs and 2FA passwords are passed to TDLib in memory only.
 */
class TelegramSessionManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onQrLink(link: String)
    }

    private data class Session(val id: String, val client: Client, val dbKey: ByteArray)
    private val sessions = linkedMapOf<String, Session>()
    private val pendingPhones = mutableMapOf<String, String>()
    private val qrSessions = mutableSetOf<String>()
    private var active: Session? = null

    init { System.loadLibrary("tdjni") }

    @Synchronized
    fun startPhoneLogin(phoneNumber: String) {
        require(phoneNumber.startsWith("+") && phoneNumber.length >= 8) {
            "Enter phone number with country code."
        }
        if (!hasTelegramCredentials()) return
        val id = phoneNumber.filter { it.isDigit() }
        pendingPhones[id] = phoneNumber
        val existing = sessions[id]
        if (existing != null) {
            active = existing
            existing.client.send(TdApi.GetAuthorizationState()) { }
            return
        }
        createSession(id)
    }

    @Synchronized
    fun startQrLogin() {
        if (!hasTelegramCredentials()) return
        val id = "qr-${java.util.UUID.randomUUID()}"
        qrSessions.add(id)
        createSession(id)
    }

    private fun hasTelegramCredentials(): Boolean {
        if (BuildConfig.TELEGRAM_API_ID.isBlank() || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
            listener.onStatus("Missing TELEGRAM_API_ID / TELEGRAM_API_HASH GitHub Actions secrets.")
            return false
        }
        if (BuildConfig.TELEGRAM_API_ID.toIntOrNull() == null) {
            listener.onStatus("TELEGRAM_API_ID must be a numeric Telegram API ID.")
            return false
        }
        return true
    }

    private fun createSession(id: String) {
        val dbKey = loadOrCreateDatabaseKey(id)
        val base = File(context.filesDir, "telegram_sessions/$id")
        File(base, "db").mkdirs()
        File(base, "files").mkdirs()

        val client = Client.create(
            { update -> handleUpdate(id, update) },
            { error -> listener.onStatus("TDLib update error: ${error.message}") },
            { error -> listener.onStatus("TDLib error: ${error.message}") }
        )
        val session = Session(id, client, dbKey)
        sessions[id] = session
        active = session
        client.send(TdApi.SetTdlibParameters(
            false,
            File(base, "db").absolutePath,
            File(base, "files").absolutePath,
            true, true, true, true,
            BuildConfig.TELEGRAM_API_ID.toInt(),
            BuildConfig.TELEGRAM_API_HASH,
            "en",
            "Android",
            android.os.Build.VERSION.RELEASE,
            "1.0.0",
            true,
            false
        )) { handleResult(it) }
        listener.onStatus("Initializing isolated Telegram session…")
    }

    private fun handleUpdate(id: String, update: TdApi.Object) {
        val session = synchronized(this) { sessions[id] } ?: return
        if (update !is TdApi.UpdateAuthorizationState) return
        when (val state = update.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters ->
                listener.onStatus("TDLib parameters requested")
            is TdApi.AuthorizationStateWaitEncryptionKey ->
                session.client.send(TdApi.CheckDatabaseEncryptionKey(session.dbKey)) { handleResult(it) }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                val phone = pendingPhones[id]
                if (phone != null) {
                    session.client.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { handleResult(it) }
                } else if (id in qrSessions) {
                    session.client.send(TdApi.RequestQrCodeAuthentication(emptyArray())) { handleResult(it) }
                } else {
                    listener.onStatus("Enter the phone number for this Telegram session")
                }
            }
            is TdApi.AuthorizationStateWaitCode -> listener.onStatus("Telegram OTP required")
            is TdApi.AuthorizationStateWaitPassword -> listener.onStatus("Telegram 2-step password required")
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                listener.onStatus("Scan this QR code from an already logged-in Telegram app")
                listener.onQrLink(state.link)
            }
            is TdApi.AuthorizationStateReady -> listener.onStatus("Telegram account connected")
            is TdApi.AuthorizationStateLoggingOut -> listener.onStatus("Logging out…")
            is TdApi.AuthorizationStateClosing -> listener.onStatus("Closing session…")
            is TdApi.AuthorizationStateClosed -> listener.onStatus("Session closed")
            else -> listener.onStatus("Telegram authorization state: ${state.javaClass.simpleName}")
        }
    }

    fun submitCode(value: String) {
        require(value.isNotBlank()) { "Enter the Telegram code." }
        val session = active ?: error("No active Telegram session")
        session.client.send(TdApi.CheckAuthenticationCode(value)) { handleResult(it) }
    }

    fun submitPassword(value: String) {
        require(value.isNotBlank()) { "Enter the 2FA password." }
        val session = active ?: error("No active Telegram session")
        session.client.send(TdApi.CheckAuthenticationPassword(value)) { handleResult(it) }
    }

    @Synchronized
    fun switchAccount(accountId: String) {
        active = sessions[accountId] ?: error("Unknown Telegram session")
        listener.onStatus("Active account switched")
    }

    @Synchronized
    fun closeAccount(accountId: String) {
        val session = sessions.remove(accountId) ?: return
        pendingPhones.remove(accountId)
        qrSessions.remove(accountId)
        session.client.send(TdApi.Close()) { }
        if (active?.id == accountId) active = null
    }

    private fun handleResult(result: TdApi.Object) {
        if (result is TdApi.Error) listener.onStatus("Telegram error ${result.code}: ${result.message}")
    }

    private fun loadOrCreateDatabaseKey(id: String): ByteArray {
        val prefs = context.getSharedPreferences("td_session_keys", Context.MODE_PRIVATE)
        val stored = prefs.getString(id, null)
        if (stored != null) return decrypt(stored)
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString(id, encrypt(raw)).apply()
        return raw
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("teledrive_session_wrap", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            "teledrive_session_wrap",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateMasterKey(),
            GCMParameterSpec(128, payload.copyOfRange(0, 12))
        )
        return cipher.doFinal(payload.copyOfRange(12, payload.size))
    }
}
