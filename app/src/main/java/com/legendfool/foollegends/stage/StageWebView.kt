package com.legendfool.foollegends.stage

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * WebView that keeps the keyboard out of the way.
 *
 * In landscape most IMEs switch to the extract UI, a full-screen editor with its own
 * buttons that hides the page behind it. Refusing that mode keeps the real input
 * visible, which is what the page-side scroll assist relies on.
 */
class StageWebView(context: Context) : WebView(context) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return connection
    }
}
