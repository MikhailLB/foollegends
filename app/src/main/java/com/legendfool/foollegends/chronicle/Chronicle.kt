package com.legendfool.foollegends.chronicle

import android.content.Context
import android.os.Build
import android.util.Log
import com.legendfool.foollegends.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Launch diary for the two exchanges the routing decision turns on: everything
 * AppsFlyer has to say about this install, and the whole conversation with the config
 * endpoint — request body included.
 *
 * Both already go to logcat, and logcat is where they are read while a launch is
 * happening. It is not where they can be read afterwards: the buffer is a ring shared
 * with every process on the device, and on a phone with a few chatty neighbours the
 * lines that matter are gone within minutes. A launch that routed the wrong way is
 * normally only noticed once it is over, which is precisely when logcat no longer has
 * it — so the same lines are kept here too, in a file that outlives the session and
 * survives a reinstall of the app it describes.
 *
 * Debug builds only, and deliberately so: the file holds the config endpoint in clear
 * text — the very string MaskSmith exists to keep out of the binary — next to the
 * install's attribution and its push token, on a path any file manager can open.
 */
object Chronicle {

    /** Formatted only on [scribe], which is the single thread that touches it. */
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val scribe = Executors.newSingleThreadExecutor()

    @Volatile
    private var ledger: File? = null

    /** Where the diary can be pulled from, or null when nothing is being kept. */
    val path: String?
        get() = ledger?.absolutePath

    /**
     * Opens the diary and heads a fresh session in it. Call once, from the
     * Application, before anything worth recording has had a chance to happen.
     */
    fun open(ctx: Context) {
        if (!BuildConfig.DEBUG) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val file = File(dir, NAME)
        ledger = file
        Log.i(TAG, "chronicle at ${file.absolutePath}")
        scribe.execute {
            runCatching {
                // A diary nobody prunes fills the card eventually. Only the recent
                // stretch is of any use, so one previous copy is kept and the rest goes.
                if (file.length() > MAX_BYTES) {
                    file.copyTo(File(dir, "$NAME.1"), overwrite = true)
                    file.writeText("")
                }
                file.appendText(
                    "\n=== session ${stamp.format(Date())} · " +
                        "v${BuildConfig.VERSION_NAME} · " +
                        "${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT} " +
                        "===\n"
                )
            }
        }
    }

    /** Logcat and the diary in one call, so neither can drift from the other. */
    fun note(tag: String, line: String) {
        Log.i(tag, line)
        write("I", tag, line)
    }

    fun warn(tag: String, line: String) {
        Log.w(tag, line)
        write("W", tag, line)
    }

    private fun write(level: String, tag: String, line: String) {
        val file = ledger ?: return
        scribe.execute {
            runCatching { file.appendText("${stamp.format(Date())} $level/$tag: $line\n") }
        }
    }

    private const val TAG = "Chronicle"
    private const val NAME = "chronicle.log"

    /** Two or three dozen launches' worth, which is more than anyone reads back. */
    private const val MAX_BYTES = 512L * 1024L
}
