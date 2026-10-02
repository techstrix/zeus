package com.xorbi.zeus.ui

import android.inputmethodservice.InputMethodService
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import com.xorbi.zeus.DictationRunner
import com.xorbi.zeus.ModelStore
import com.xorbi.zeus.Prefs
import com.xorbi.zeus.R

/**
 * A TV keyboard whose useful part is a microphone.
 *
 * This is the route that needs no adb. Becoming the platform's default
 * *recogniser* requires WRITE_SECURE_SETTINGS, which an app cannot grant itself.
 * Becoming a *keyboard* requires nothing but the user tapping Zeus in
 * Settings, and `Settings.ACTION_INPUT_METHOD_SETTINGS` opens that screen directly
 * on Android TV (TvSettings' KeyboardActivity).
 *
 * Once selected, the mic button reaches every app that uses a standard EditText,
 * including apps whose own mic button is broken or missing, and unlike the
 * recogniser route it also works on Google TV where the Assistant owns voice.
 *
 * There are no letter keys on purpose: on a TV, typing is either this or a
 * dedicated on-screen keyboard, and a half-hearted alphabet would only be worse
 * than what the device already has. Nothing is lost by switching back via
 * Settings.
 */
class ZeusInputMethodService : InputMethodService() {

    private lateinit var prefs: Prefs
    private lateinit var models: ModelStore

    private var micButton: View? = null
    private var statusView: TextView? = null

    private var runner: DictationRunner? = null
    private var committed = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        models = ModelStore(this)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.view_ime_dictation, null)
        micButton = view.findViewById(R.id.ime_mic)
        statusView = view.findViewById(R.id.ime_status)

        micButton?.setOnClickListener { onMicPressed() }
        // A TV remote's centre key should also work, not just an on-screen click.
        micButton?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                if (event.action == KeyEvent.ACTION_UP) onMicPressed()
                true
            } else {
                false
            }
        }

        statusView?.setText(R.string.ime_idle)
        return view
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // Never carry text into a different field.
        committed = false
        resetStatus(getString(R.string.ime_idle))
    }

    override fun onFinishInput() {
        stopDictation()
        super.onFinishInput()
    }

    // --- dictation ---------------------------------------------------------

    private fun onMicPressed() {
        val active = runner
        if (active != null) {
            // Second press finishes early, which is what people expect when they
            // are dictating into a short field.
            active.stop()
            setMicEnabled(false)
            return
        }
        startDictation()
    }

    private fun startDictation() {
        val connection = currentInputConnection
        if (connection == null) {
            resetStatus(getString(R.string.ime_no_field))
            return
        }

        setMicEnabled(true)
        resetStatus(getString(R.string.ime_loading))

        val model = try {
            models.unpackBundledModel()
        } catch (e: Exception) {
            Log.e(TAG, "could not unpack model", e)
            resetStatus(getString(R.string.error_server))
            return
        }

        runner = DictationRunner(this, model.absolutePath, prefs, object : DictationRunner.Listener {
            override fun onReady() = post { resetStatus(getString(R.string.ime_listening)) }

            override fun onBeginningOfSpeech() = post { resetStatus(getString(R.string.ime_heard)) }

            override fun onLevel(dbfs: Float) = Unit

            override fun onPartial(text: String) = post { statusView?.text = text }

            override fun onEndOfSpeech() = post { resetStatus(getString(R.string.ime_processing)) }

            override fun onResult(text: String) = post {
                commit(text)
                resetStatus(
                    if (text.isBlank()) getString(R.string.ime_nothing) else text,
                )
            }

            override fun onError(code: Int) = post {
                resetStatus(getString(R.string.ime_error, codeName(code)))
                clearRunner()
            }
        }).also {
            it.start(
                DictationRunner.Options().apply {
                    languageTag = dictationLanguage()
                },
            )
        }
    }

    private fun stopDictation() {
        runner?.cancel()
        clearRunner()
    }

    private fun clearRunner() {
        runner = null
        setMicEnabled(true)
    }

    /**
     * Puts the transcript into the field. A trailing space is appended so the next
     * word does not run into the last one, and duplicate commits are suppressed in
     * case a caller double-delivers.
     */
    private fun commit(text: String) {
        if (text.isBlank() || committed) return
        committed = true
        val connection: InputConnection = currentInputConnection ?: return
        connection.commitText("$text ", 1)
    }

    private fun setMicEnabled(enabled: Boolean) {
        micButton?.isEnabled = enabled
        micButton?.alpha = if (enabled) 1.0f else 0.4f
    }

    private fun resetStatus(text: String) {
        statusView?.text = text
    }

/**
 * Deliberately fixed rather than read from the field's locale: an
 * InputMethodService has no public access to it (`InputBinding.subtypeHints` and
 * `InputMethodInfo.Subtype` are both @hide), and the only bundled model is
 * English anyway. Revisit when a multilingual model exists.
 */
private fun dictationLanguage(): String = DictationRunner.DEFAULT_LANGUAGE

    private fun codeName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> getString(R.string.error_busy)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.error_no_permission)
        SpeechRecognizer.ERROR_CLIENT -> getString(R.string.error_client)
        else -> getString(R.string.error_server)
    }

    private fun post(block: () -> Unit) {
        // The runner delivers on its own worker thread; the UI must be touched
        // on the main one.
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private companion object {
        const val TAG = "ZeusIme"
    }
}