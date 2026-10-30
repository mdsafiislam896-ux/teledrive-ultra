package com.safi.teledrive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Each account gets an independent TDLib client, database directory and database key.
 * Authentication codes and 2FA passwords are only passed to TDLib in memory.
 */
class TelegramSessionManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onQrLink(link: String)
    }

    private data class Session(val id: String, val phoneNumber: String, val client: Client, val dbKey: ByteArray)
    private val sessions = linkedMapOf<String, Session>()
    private var active: Session? = null

    init { System.loadLibrary("tdjni") }

    @Synchronized
    fun startPhoneLogin(phoneNumber: String) {
        require(phoneNumber.startsWith("+") && phoneNumber.length >= 8) { "Enter phone number with country code." }
        if (BuildConfig.TELEGRAM_API_ID.isBlank() || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
            listener.onStatus("Build configuration missing: add TELEGRAM_API_ID and TELEGRAM_API_HASH GitHub secrets.")
            return
        }
        val id = phoneNumber.filter { it.isDigit() }.takeLast(10).ifBlank { System.currentTimeMillis().toString() }
        val existing = sessions[id]
        if (existing != null) {
            active = existing
            existing.client.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) { handleResult(existing, it) }
            return
        }

        val dbKey = loadOrCreateDatabaseKey(id)
        lateinit var session: Session
        val client = Client.create(
            { update -> handleUpdate(id, update) },
            { error -> listener.onStatus("TDLib error: ${error.message}") },
            { error -> listener.onStatus("TDLib fatal error: ${error.message}") }
        )
        session = Session(id, phoneNumber, client, dbKey)
        sessions[id] = session
        active = session
        client.send(TdApi.SetLogVerbosityLevel(1)) { }
        client.send(TdApi.SetTdlibParameters(
            false,
            File(context.filesDir, "telegram_sessions/$id/db").absolutePath,
            File(context.filesDir, "telegram_sessions/$id/files").absolutePath,
            true, true, true, true,
            BuildConfig.TELEGRAM_API_ID.toInt(),
            BuildConfig.TELEGRAM_API_HASH,
            "en",
            "Android",
            android.os.Build.VERSION.RELEASE,
            "1.0.0",
            true,
            false
        )) { handleResult(session, it) }
        listener.onStatus("Creating isolated Telegram session…")
    }

    private fun handleUpdate(id: String, update: TdApi.Object) {
        val s = synchronized(this) { sessions[id] } ?: return
        when (update) {
            is TdApi.UpdateAuthorizationState -> when (val state = update.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> listener.onStatus("TDLib parameters requested")
                is TdApi.AuthorizationStateWaitEncryptionKey ->
                    s.client.send(TdApi.CheckDatabaseEncryptionKey(s.dbKey)) { handleResult(s, it) }
                is TdApi.AuthorizationStateWaitPhoneNumber -> {\n                    listener.onStatus("Submitting phone number to Telegram…")\n                    s.client.send(TdApi.SetAuthenticationPhoneNumber(s.phoneNumber, null)) { handleResult(s, it) }\n                }
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
    }

    fun submitCode(value: String) {
        require(value.isNotBlank()) { "Enter the Telegram code." }
        active?.client?.send(TdApi.CheckAuthenticationCode(value)) { handleResult(active!!, it) }
    }

    fun submitPassword(value: String) {
        require(value.isNotBlank()) { "Enter the 2FA password." }
        active?.client?.send(TdApi.CheckAuthenticationPassword(value)) { handleResult(active!!, it) }
    }

    fun requestQrLogin() {
        active?.client?.send(TdApi.RequestQrCodeAuthentication(emptyArray())) {
            active?.let { handleResult(it, it.client.execute(TdApi.GetAuthorizationState())) }
        }
    }

    private fun handleResult(session: Session, result: TdApi.Object) {
        if (result is TdApi.Error) listener.onStatus("Telegram: ${result.message}")
    }

    @Synchronized
    fun switchAccount(accountId: String) {
        active = sessions[accountId] ?: error("Unknown Telegram session")
        listener.onStatus("Active account switched")
    }

    @Synchronized
    fun closeAccount(accountId: String) {
        val session = sessions.remove(accountId) ?: return
        session.client.send(TdApi.Close()) { }
        if (active?.id == accountId) active = null
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
        val payload = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return cipher.doFinal(payload.copyOfRange(12, payload.size))
    }
}
