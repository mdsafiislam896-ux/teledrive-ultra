package com.safi.teledrive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.delay

/**
 * Owns isolated, persistent TDLib clients. TDLib databases remain in app-private
 * storage; each database encryption key is wrapped by a non-exportable Android
 * Keystore AES key. OTP and 2FA values are never persisted by this class.
 */
class TelegramSessionManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onQrLink(link: String)
    }

    interface TransferCallback {
        fun onSuccess(message: String)
        fun onError(message: String)
    }

    private data class Session(
        val id: String,
        val phoneNumber: String,
        val client: Client,
        val dbKey: ByteArray,
        val qrLogin: Boolean = false,
        var parametersSent: Boolean = false,
        @Volatile var ready: Boolean = false
    )

    private val prefs by lazy {
        context.getSharedPreferences("td_session_keys", Context.MODE_PRIVATE)
    }
    private val sessions = linkedMapOf<String, Session>()
    private val pendingUploads = linkedMapOf<Long, TransferCallback>()
    private val earlyUploadUpdates = linkedMapOf<Long, TdApi.Object>()
    private val pendingDownloads = linkedMapOf<Int, TransferCallback>()
    @Volatile private var active: Session? = null

    init {
        System.loadLibrary("tdjni")
        restoreSavedSessions()
    }

    @Synchronized
    fun startPhoneLogin(phoneNumber: String) {
        require(phoneNumber.startsWith("+") && phoneNumber.count { it.isDigit() } >= 8) {
            "Enter phone number with country code."
        }
        ensureApiCredentials()
        val id = accountId(phoneNumber)
        val session = sessions[id] ?: createSession(id, phoneNumber)
        active = session
        listener.onStatus("Opening Telegram session…")
    }

    /** Compatibility entry point used by the native WebView bridge. */
    fun startPhoneAuth(phoneNumber: String) = startPhoneLogin(phoneNumber.trim().replace(" ", ""))

    @Synchronized
    fun hasActiveSession(): Boolean = active?.ready == true

    /** Forgets the currently selected account, TDLib database and wrapped key. */
    @Synchronized
    fun removeAccount() {
        val account = active
        if (account == null) {
            listener.onStatus("No Telegram account is connected.")
            return
        }
        closeAccount(account.id, forget = true)
        listener.onStatus("Telegram account removed from this device.")
    }

    @Synchronized
    fun startQrLogin() {
        ensureApiCredentials()
        val id = "qr_" + ByteArray(12).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        active = createSession(id, "", qrLogin = true)
        listener.onStatus("Preparing a new QR login session…")
    }

    private fun ensureApiCredentials() {
        check(BuildConfig.TELEGRAM_API_ID.isNotBlank() && BuildConfig.TELEGRAM_API_HASH.isNotBlank()) {
            "Build configuration missing: add TELEGRAM_API_ID and TELEGRAM_API_HASH GitHub secrets."
        }
    }

    @Synchronized
    private fun restoreSavedSessions() {
        val ids = prefs.getStringSet("saved_accounts", emptySet()).orEmpty().toList()
        ids.forEach { id ->
            try {
                val encryptedPhone = prefs.getString("phone_$id", null) ?: ""
                val phone = if (encryptedPhone.isBlank()) "" else decryptText(encryptedPhone)
                val session = createSession(id, phone)
                if (active == null) active = session
            } catch (e: Exception) {
                listener.onStatus("Could not restore a saved Telegram session: ${e.message}")
            }
        }
        if (ids.isNotEmpty()) listener.onStatus("Restoring ${ids.size} saved Telegram account(s)…")
    }

    @Synchronized
    private fun createSession(id: String, phoneNumber: String, qrLogin: Boolean = false): Session {
        sessions[id]?.let { return it }
        val dbKey = loadOrCreateDatabaseKey(id)
        val client = Client.create(
            { update -> handleUpdate(id, update) },
            { error -> listener.onStatus("TDLib error: ${error.message}") },
            { error -> listener.onStatus("TDLib fatal error: ${error.message}") }
        )
        val session = Session(id, phoneNumber, client, dbKey, qrLogin)
        sessions[id] = session
        client.send(TdApi.SetLogVerbosityLevel(1)) { }
        return session
    }

    private fun handleUpdate(id: String, update: TdApi.Object) {
        val session = synchronized(this) { sessions[id] } ?: return
        when (update) {
            is TdApi.UpdateMessageSendSucceeded -> {
                val callback = synchronized(this) {
                    pendingUploads.remove(update.oldMessageId) ?: run {
                        earlyUploadUpdates[update.oldMessageId] = update
                        null
                    }
                }
                callback?.onSuccess("Telegram upload completed (message ${update.message.id}).")
                return
            }
            is TdApi.UpdateMessageSendFailed -> {
                val callback = synchronized(this) {
                    pendingUploads.remove(update.oldMessageId) ?: run {
                        earlyUploadUpdates[update.oldMessageId] = update
                        null
                    }
                }
                callback?.onError("Telegram upload failed: ${update.error.message}")
                return
            }
            is TdApi.UpdateFile -> {
                val file = update.file
                if (file.local != null && file.local.isDownloadingCompleted) {
                    val callback = synchronized(this) { pendingDownloads.remove(file.id) }
                    callback?.onSuccess(file.local.path)
                }
                return
            }
        }
        if (update !is TdApi.UpdateAuthorizationState) return
        when (val state = update.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (!session.parametersSent) {
                    session.parametersSent = true
                    val root = File(context.filesDir, "telegram_sessions/$id")
                    val result = TdApi.SetTdlibParameters(
                        false,
                        File(root, "db").absolutePath,
                        File(root, "files").absolutePath,
                        session.dbKey,
                        true, true, true, true,
                        BuildConfig.TELEGRAM_API_ID.toInt(),
                        BuildConfig.TELEGRAM_API_HASH,
                        "en",
                        "Android",
                        android.os.Build.VERSION.RELEASE,
                        "1.0.0"
                    )
                    session.client.send(result) { handleResult(session, it) }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                if (session.qrLogin) {
                    session.client.send(TdApi.RequestQrCodeAuthentication(longArrayOf())) {
                        handleResult(session, it)
                    }
                } else if (session.phoneNumber.isNotBlank()) {
                    session.client.send(TdApi.SetAuthenticationPhoneNumber(session.phoneNumber, null)) {
                        handleResult(session, it)
                    }
                } else if (active?.id == id) {
                    listener.onStatus("Telegram is ready for QR login")
                }
            }
            is TdApi.AuthorizationStateWaitCode -> {
                if (active?.id == id) listener.onStatus("Telegram OTP required")
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                if (active?.id == id) listener.onStatus("Telegram 2-step verification password required")
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                if (active?.id == id) {
                    listener.onStatus("Scan this QR code from an already logged-in Telegram app")
                    listener.onQrLink(state.link)
                }
            }
            is TdApi.AuthorizationStateReady -> {
                session.ready = true
                prefs.edit().putStringSet(
                    "saved_accounts",
                    (prefs.getStringSet("saved_accounts", emptySet()).orEmpty() + id)
                ).putString("phone_$id", if (session.phoneNumber.isBlank()) "" else encryptText(session.phoneNumber))
                    .apply()
                if (active?.id == id) listener.onStatus("Telegram account connected")
            }
            is TdApi.AuthorizationStateLoggingOut -> listener.onStatus("Logging out…")
            is TdApi.AuthorizationStateClosing -> listener.onStatus("Closing session…")
            is TdApi.AuthorizationStateClosed -> listener.onStatus("Session closed")
            else -> if (active?.id == id) {
                listener.onStatus("Telegram authorization: ${state.javaClass.simpleName}")
            }
        }
    }

    fun submitCode(value: String) {
        require(value.isNotBlank()) { "Enter the Telegram code." }
        val session = active ?: error("No active Telegram session")
        session.client.send(TdApi.CheckAuthenticationCode(value)) { handleResult(session, it) }
    }

    fun submitPassword(value: String) {
        require(value.isNotBlank()) { "Enter the 2FA password." }
        val session = active ?: error("No active Telegram session")
        session.client.send(TdApi.CheckAuthenticationPassword(value)) { handleResult(session, it) }
    }

    fun requestQrLogin() {
        startQrLogin()
    }

    /** Uploads a local file to the authenticated account's Saved Messages chat. */
    fun uploadToSavedMessages(localPath: String, caption: String = "", callback: TransferCallback) {
        val session = active
        if (session == null || !session.ready) {
            callback.onError("No connected Telegram account. Complete login first.")
            return
        }
        val localFile = File(localPath)
        if (!localFile.isFile || !localFile.canRead()) {
            callback.onError("Selected file cannot be read.")
            return
        }
        session.client.send(TdApi.GetMe()) { meResult ->
            if (meResult !is TdApi.User) {
                callback.onError((meResult as? TdApi.Error)?.message ?: "Unable to load Telegram profile.")
                return@send
            }
            session.client.send(TdApi.CreatePrivateChat(meResult.id, true)) { chatResult ->
                if (chatResult !is TdApi.Chat) {
                    callback.onError((chatResult as? TdApi.Error)?.message ?: "Unable to open Saved Messages.")
                    return@send
                }
                val inputDocument = TdApi.InputDocument(
                    TdApi.InputFileLocal(localFile.absolutePath),
                    null,
                    false
                )
                val content = TdApi.InputMessageDocument(
                    inputDocument,
                    TdApi.FormattedText(caption, emptyArray())
                )
                session.client.send(
                    TdApi.SendMessage(chatResult.id, null, null, null, null, content)
                ) { messageResult ->
                    if (messageResult is TdApi.Message) {
                        if (messageResult.sendingState == null) {
                            callback.onSuccess("Telegram upload completed (message ${messageResult.id}).")
                        } else {
                            val early = synchronized(this) {
                                earlyUploadUpdates.remove(messageResult.id) ?: run {
                                    pendingUploads[messageResult.id] = callback
                                    null
                                }
                            }
                            when (early) {
                                is TdApi.UpdateMessageSendSucceeded ->
                                    callback.onSuccess("Telegram upload completed (message ${early.message.id}).")
                                is TdApi.UpdateMessageSendFailed ->
                                    callback.onError("Telegram upload failed: ${early.error.message}")
                            }
                        }
                    } else {
                        callback.onError((messageResult as? TdApi.Error)?.message ?: "Telegram upload failed.")
                    }
                }
            }
        }
    }

    /** Deletes a message previously uploaded by this app to Saved Messages. */
    fun deleteSavedMessage(messageId: Long, callback: TransferCallback) {
        val session = active
        if (session == null || !session.ready) {
            callback.onError("No connected Telegram account. Complete login first.")
            return
        }
        if (messageId <= 0L) {
            callback.onError("This file has no Telegram message reference.")
            return
        }
        session.client.send(TdApi.GetMe()) { meResult ->
            if (meResult !is TdApi.User) {
                callback.onError((meResult as? TdApi.Error)?.message ?: "Unable to load Telegram profile.")
                return@send
            }
            session.client.send(TdApi.CreatePrivateChat(meResult.id, true)) { chatResult ->
                if (chatResult !is TdApi.Chat) {
                    callback.onError((chatResult as? TdApi.Error)?.message ?: "Unable to open Saved Messages.")
                    return@send
                }
                session.client.send(TdApi.DeleteMessages(chatResult.id, longArrayOf(messageId), true)) { result ->
                    if (result is TdApi.Ok) callback.onSuccess("Telegram message $messageId deleted.")
                    else callback.onError((result as? TdApi.Error)?.message ?: "Telegram could not delete this file.")
                }
            }
        }
    }

    suspend fun awaitActiveReady(timeoutMs: Long = 120_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (active?.ready == true) return true
            delay(500)
        }
        return active?.ready == true
    }

    /** Downloads a Telegram file ID into TDLib's private files directory. */
    fun downloadFile(fileId: Int, callback: TransferCallback) {
        val session = active
        if (session == null || !session.ready) {
            callback.onError("No connected Telegram account. Complete login first.")
            return
        }
        session.client.send(TdApi.DownloadFile(fileId, 16, 0L, 0L, true)) { result ->
            when (result) {
                is TdApi.File -> {
                    val local = result.local
                    if (local != null && local.isDownloadingCompleted) {
                        callback.onSuccess(local.path)
                    } else if (local != null && local.canBeDownloaded) {
                        synchronized(this) { pendingDownloads[fileId] = callback }
                    } else {
                        callback.onError("Telegram file is not downloadable in this session.")
                    }
                }
                is TdApi.Error -> callback.onError("Telegram download error: ${result.message}")
                else -> callback.onError("Unexpected Telegram download response.")
            }
        }
    }

    private fun handleResult(session: Session, result: TdApi.Object) {
        if (result is TdApi.Error && active?.id == session.id) {
            listener.onStatus("Telegram error: ${result.message}")
        }
    }

    @Synchronized
    fun switchAccount(accountId: String) {
        active = sessions[accountId] ?: error("Unknown Telegram session")
        listener.onStatus("Active Telegram account switched")
    }

    @Synchronized
    fun savedAccountIds(): List<String> = prefs.getStringSet("saved_accounts", emptySet())
        .orEmpty().toList()

    @Synchronized
    fun closeAccount(accountId: String, forget: Boolean = false) {
        val session = sessions.remove(accountId) ?: return
        session.client.send(TdApi.Close()) { }
        if (active?.id == accountId) active = sessions.values.firstOrNull()
        if (forget) {
            prefs.edit()
                .putStringSet("saved_accounts", prefs.getStringSet("saved_accounts", emptySet()).orEmpty() - accountId)
                .remove("phone_$accountId")
                .remove(accountId)
                .apply()
            File(context.filesDir, "telegram_sessions/$accountId").deleteRecursively()
        }
    }

    private fun accountId(phone: String): String = MessageDigest.getInstance("SHA-256")
        .digest(phone.filter { it.isDigit() }.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }

    private fun loadOrCreateDatabaseKey(id: String): ByteArray {
        val stored = prefs.getString(id, null)
        if (stored != null) return decryptBytes(stored)
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(id, encryptBytes(raw)).apply()
        return raw
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey("teledrive_session_wrap", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                "teledrive_session_wrap",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptBytes(value: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)
    }

    private fun decryptBytes(value: String): ByteArray {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateMasterKey(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }

    private fun encryptText(value: String): String = encryptBytes(value.toByteArray(Charsets.UTF_8))
    private fun decryptText(value: String): String = decryptBytes(value).toString(Charsets.UTF_8)
}
