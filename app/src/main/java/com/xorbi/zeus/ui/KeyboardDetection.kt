package com.xorbi.zeus.ui

import android.content.ContentResolver
import android.provider.Settings

/**
 * Works out whether Zeus is the input method currently handling text.
 *
 * Reads `Settings.Secure.DEFAULT_INPUT_METHOD`, which is the same setting the
 * platform itself consults. `InputMethodManager.getCurrentInputMethodInfo()` would
 * be tidier but is API 34 only.
 */
object KeyboardDetection {

    /**
     * [currentIme] is the raw setting, e.g.
     * `com.xorbi.zeus/com.xorbi.zeus.ui.ZeusInputMethodService`, possibly followed
     * by `:` and an id. Returns the package name, or null if unset or malformed.
     */
    fun packageOf(currentIme: String?): String? {
        if (currentIme.isNullOrBlank()) return null
        val slash = currentIme.indexOf('/')
        // A value with no separator is not a component name we can trust.
        if (slash <= 0) return null
        val pkg = currentIme.substring(0, slash)
        // A trailing ":" would mean an id was appended to the component.
        return pkg.substringBefore(':').takeIf { it.isNotBlank() }
    }

    fun isActiveKeyboard(resolver: ContentResolver, packageName: String): Boolean {
        val current = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        return packageOf(current) == packageName
    }
}
