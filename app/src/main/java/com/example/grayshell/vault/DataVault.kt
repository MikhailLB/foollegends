package com.example.grayshell.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistent data store. Sensitive values (URLs) use EncryptedSharedPreferences;
 * non-sensitive flags use regular SharedPreferences.
 */
class DataVault(ctx: Context) {

    private val plain: SharedPreferences =
        ctx.getSharedPreferences("fl_state", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        try {
            val master = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "fl_enc",
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            plain
        }
    }

    // ---------------------------------------------------------------- run channel

    var runChannel: String
        get() = plain.getString(KEY_CHANNEL, CHANNEL_UNDECIDED) ?: CHANNEL_UNDECIDED
        set(v) = plain.edit().putString(KEY_CHANNEL, v).apply()

    // ---------------------------------------------------------------- destination URL

    var destinationUrl: String?
        get() = secure.getString(KEY_DEST, null)
        set(v) = if (v == null) secure.edit().remove(KEY_DEST).apply()
                 else secure.edit().putString(KEY_DEST, v).apply()

    var urlExpiresAt: Long
        get() = plain.getLong(KEY_EXPIRES, 0L)
        set(v) = plain.edit().putLong(KEY_EXPIRES, v).apply()

    fun isUrlValid(): Boolean {
        val url = destinationUrl ?: return false
        if (url.isBlank()) return false
        val exp = urlExpiresAt
        return exp == 0L || System.currentTimeMillis() / 1000 < exp
    }

    // ---------------------------------------------------------------- push URL (one-shot cold start)

    var coldPushUrl: String?
        get() = secure.getString(KEY_PUSH_COLD, null)
        set(v) = if (v == null) secure.edit().remove(KEY_PUSH_COLD).apply()
                 else secure.edit().putString(KEY_PUSH_COLD, v).apply()

    fun consumeColdPushUrl(): String? {
        val v = coldPushUrl
        coldPushUrl = null
        return v
    }

    // ---------------------------------------------------------------- notification state

    var notifSkipUntil: Long
        get() = plain.getLong(KEY_NOTIF_SKIP, 0L)
        set(v) = plain.edit().putLong(KEY_NOTIF_SKIP, v).apply()

    var notifGranted: Boolean
        get() = plain.getBoolean(KEY_NOTIF_GRANTED, false)
        set(v) = plain.edit().putBoolean(KEY_NOTIF_GRANTED, v).apply()

    var notifOsDenied: Boolean
        get() = plain.getBoolean(KEY_NOTIF_OS_DENIED, false)
        set(v) = plain.edit().putBoolean(KEY_NOTIF_OS_DENIED, v).apply()

    fun shouldShowNotifScreen(): Boolean {
        if (notifGranted) return false
        if (notifOsDenied) return false
        val now = System.currentTimeMillis() / 1000
        return now >= notifSkipUntil
    }

    fun skipNotifFor3Days() {
        val now = System.currentTimeMillis() / 1000
        notifSkipUntil = now + 259_200L
    }

    // ---------------------------------------------------------------- FCM token

    var fcmToken: String?
        get() = secure.getString(KEY_FCM, null)
        set(v) = if (v == null) secure.edit().remove(KEY_FCM).apply()
                 else secure.edit().putString(KEY_FCM, v).apply()

    // ---------------------------------------------------------------- keyboard height

    /**
     * The height the keyboard last came to rest at, per orientation. Mid-animation the
     * system over-reports it, so KeyboardPan needs a figure it can trust from the
     * first frame of the very first opening — see KeyboardPan's own notes.
     */
    fun keyboardRest(portrait: Boolean): Int =
        plain.getInt(if (portrait) KEY_KB_TALL else KEY_KB_WIDE, 0)

    fun rememberKeyboardRest(portrait: Boolean, height: Int) {
        plain.edit().putInt(if (portrait) KEY_KB_TALL else KEY_KB_WIDE, height).apply()
    }

    // ----------------------------------------------------------------

    companion object {
        const val CHANNEL_UNDECIDED = "undecided"
        const val CHANNEL_STREAM    = "stream"
        const val CHANNEL_NATIVE    = "native"

        private const val KEY_CHANNEL      = "run_ch"
        private const val KEY_DEST         = "dest_u"
        private const val KEY_EXPIRES      = "dest_exp"
        private const val KEY_PUSH_COLD    = "push_c"
        private const val KEY_NOTIF_SKIP   = "nf_skip"
        private const val KEY_NOTIF_GRANTED = "nf_ok"
        private const val KEY_NOTIF_OS_DENIED = "nf_os_no"
        private const val KEY_FCM          = "fcm_t"
        private const val KEY_KB_TALL      = "kb_t"
        private const val KEY_KB_WIDE      = "kb_w"
    }
}
