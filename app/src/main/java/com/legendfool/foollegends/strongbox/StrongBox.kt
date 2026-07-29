package com.legendfool.foollegends.strongbox

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistent state. URLs and the FCM token live in an encrypted store; flags and
 * timestamps in a plain one.
 */
class StrongBox(ctx: Context) {

    private val flags: SharedPreferences =
        ctx.getSharedPreferences("jl_core", Context.MODE_PRIVATE)

    private val sealed: SharedPreferences by lazy {
        try {
            val master = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "jl_sealed",
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            flags
        }
    }

    /**
     * A device-to-device restore carries the ciphertext but not the hardware key,
     * so a decrypt can fail long after the store itself opened fine. Treat any such
     * value as absent instead of crashing.
     */
    private fun readSealed(key: String): String? =
        runCatching { sealed.getString(key, null) }.getOrNull()

    private fun writeSealed(key: String, value: String?) {
        runCatching {
            if (value == null) sealed.edit().remove(key).apply()
            else sealed.edit().putString(key, value).apply()
        }
    }

    var mode: String
        get() = flags.getString(K_MODE, MODE_UNSET) ?: MODE_UNSET
        set(v) = flags.edit().putString(K_MODE, v).apply()

    var landingUrl: String?
        get() = readSealed(K_LANDING)
        set(v) = writeSealed(K_LANDING, v)

    var landingExpiresAt: Long
        get() = flags.getLong(K_LANDING_EXP, 0L)
        set(v) = flags.edit().putLong(K_LANDING_EXP, v).apply()

    fun hasLiveLanding(): Boolean {
        val url = landingUrl
        if (url.isNullOrBlank()) return false
        val exp = landingExpiresAt
        return exp == 0L || System.currentTimeMillis() / 1000 < exp
    }

    /** Cold-start push URL. One-time: always read it through [takeChilledPush]. */
    var chilledPushUrl: String?
        get() = readSealed(K_PUSH_COLD)
        set(v) = writeSealed(K_PUSH_COLD, v)

    fun takeChilledPush(): String? = chilledPushUrl?.also { chilledPushUrl = null }

    /** Skip: the promo is due again once this passes. */
    var heraldSkipUntil: Long
        get() = flags.getLong(K_HERALD_SKIP, 0L)
        set(v) = flags.edit().putLong(K_HERALD_SKIP, v).apply()

    /**
     * Accept: the promo has said what it had to say and is never shown again.
     *
     * Which way the system dialog behind it is answered makes no difference here. A
     * permission granted needs no more asking, and one refused cannot be asked for
     * again — Android opens that dialog once per install and stays silent afterwards,
     * so a promo leading to it would only be a button that does nothing.
     */
    var heraldClosed: Boolean
        get() = flags.getBoolean(K_HERALD_CLOSED, false)
        set(v) = flags.edit().putBoolean(K_HERALD_CLOSED, v).apply()

    fun shouldOfferHerald(): Boolean {
        if (heraldClosed) return false
        return System.currentTimeMillis() / 1000 >= heraldSkipUntil
    }

    fun postponeHerald() {
        heraldSkipUntil = System.currentTimeMillis() / 1000 + THREE_DAYS
    }

    var pushToken: String?
        get() = readSealed(K_TOKEN)
        set(v) = writeSealed(K_TOKEN, v)

    /**
     * Height the keyboard came to rest at, per orientation, in pixels.
     *
     * Remembered because the system overstates it while it is still moving, and the
     * only trustworthy figure is one an earlier opening arrived at. Kept across runs
     * so that the very first opening of a session is as exact as the rest.
     */
    fun keyboardRest(portrait: Boolean): Int =
        flags.getInt(if (portrait) K_KB_TALL else K_KB_WIDE, 0)

    fun rememberKeyboardRest(portrait: Boolean, height: Int) {
        flags.edit().putInt(if (portrait) K_KB_TALL else K_KB_WIDE, height).apply()
    }

    companion object {
        const val MODE_UNSET = "und"
        const val MODE_WEB = "web"
        const val MODE_APP = "app"

        private const val THREE_DAYS = 259_200L

        private const val K_MODE = "mode_k"
        private const val K_LANDING = "land_u"
        private const val K_LANDING_EXP = "land_x"
        private const val K_PUSH_COLD = "pc_url"
        private const val K_HERALD_SKIP = "hs_skip"
        private const val K_HERALD_CLOSED = "hs_done"
        private const val K_TOKEN = "tok_f"
        private const val K_KB_TALL = "kb_t"
        private const val K_KB_WIDE = "kb_w"
    }
}
