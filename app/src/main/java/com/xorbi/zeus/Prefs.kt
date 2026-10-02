package com.xorbi.zeus

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("zeus", Context.MODE_PRIVATE)

    /**
     * Whether to stream partial results. Off is meaningfully faster on slow
     * hardware: a partial costs the same as the final decode.
     */
    var emitPartials: Boolean
        get() = prefs.getBoolean(KEY_PARTIALS, true)
        set(value) = prefs.edit().putBoolean(KEY_PARTIALS, value).apply()

    /** Which downloadable model the user picked; null means the bundled tiny.en. */
    var selectedModel: String?
        get() = prefs.getString(KEY_MODEL, null)
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    private companion object {
        const val KEY_PARTIALS = "emit_partials"
        const val KEY_MODEL = "selected_model"
    }
}