package com.xorbi.zeus.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.xorbi.zeus.DefaultRecognizer
import com.xorbi.zeus.ModelInfo
import com.xorbi.zeus.ModelStore
import com.xorbi.zeus.Prefs
import com.xorbi.zeus.R
import com.xorbi.zeus.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The TV screen: whether Zeus really is the recognizer the platform will use,
 * microphone permission, model choice, and an end-to-end dictation test.
 *
 * Status is re-read in onResume on purpose. Google's provisioning writes
 * `voice_recognition_service` back to itself, so a one-time "you are the default"
 * would quietly become a lie; re-reading is what makes the self-heal visible.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var models: ModelStore

    private lateinit var recognizerStatus: TextView
    private lateinit var adbHint: TextView
    private lateinit var micRow: Button
    private lateinit var modelButtons: LinearLayout
    private lateinit var testOutput: TextView
    private lateinit var testButton: Button
    private lateinit var statusLine: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var speech: SpeechRecognizer? = null
    private var testStartedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        prefs = Prefs(this)
        models = ModelStore(this)

        recognizerStatus = findViewById(R.id.recognizer_status)
        adbHint = findViewById(R.id.adb_hint)
        micRow = findViewById(R.id.row_mic)
        modelButtons = findViewById(R.id.model_buttons)
        testOutput = findViewById(R.id.test_output)
        testButton = findViewById(R.id.test_button)
        statusLine = findViewById(R.id.status_line)

        testButton.setOnClickListener { if (speech == null) startTest() else stopTest() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        buildModelRows()
    }

    override fun onDestroy() {
        speech?.destroy()
        speech = null
        scope.cancel()
        super.onDestroy()
    }

    // --- recognizer status -------------------------------------------------

    private fun refreshStatus() {
        val isDefault = DefaultRecognizer.isZeusDefault(this)
        val canWrite = DefaultRecognizer.hasPermission(this)
        val current = DefaultRecognizer.describeCurrent(this)

        recognizerStatus.text = if (isDefault) {
            getString(R.string.status_default_yes)
        } else {
            getString(R.string.status_default_no, current)
        }

        if (isDefault) {
            adbHint.visibility = View.GONE
            recognizerStatus.setOnClickListener { restoreDefault() }
            statusLine.text = getString(R.string.status_default_yes)
        } else {
            val commands = DefaultRecognizer.setupCommands()
            adbHint.visibility = View.VISIBLE
            adbHint.text = getString(R.string.adb_commands, commands.first(), commands.last())
            recognizerStatus.setOnClickListener { makeDefault(canWrite) }
            statusLine.text = if (canWrite) {
                getString(R.string.make_default)
            } else {
                getString(R.string.status_permission_missing, commands.first())
            }
        }

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        micRow.text = if (hasMic) {
            getString(R.string.status_mic_ok)
        } else {
            getString(R.string.grant_mic_permission)
        }
        micRow.isEnabled = !hasMic
        micRow.setOnClickListener { if (!hasMic) requestMic() }
    }

    private fun makeDefault(canWrite: Boolean) {
        if (!canWrite) {
            // An app cannot grant itself this; show the commands instead of
            // failing silently.
            adbHint.visibility = View.VISIBLE
            return
        }
        if (DefaultRecognizer.makeZeusDefault(this)) {
            Log.i(TAG, "became the default recognizer")
        } else {
            Log.w(TAG, "could not become the default recognizer")
        }
        refreshStatus()
    }

    private fun restoreDefault() {
        // Clearing beats guessing Google's component name, which varies by build.
        DefaultRecognizer.clearDefault(this)
        refreshStatus()
    }

    private fun requestMic() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) refreshStatus()
    }

    // --- models ------------------------------------------------------------

    private fun buildModelRows() {
        modelButtons.removeAllViews()
        for (model in models.catalogue()) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            container.addView(Button(this).apply {
                text = describe(model)
                isFocusable = true
                setOnClickListener { onModelSelected(model) }
            })

            if (!model.bundled) {
                val downloaded = models.fileFor(model).exists()
                container.addView(Button(this).apply {
                    text = getString(if (downloaded) R.string.model_delete else R.string.model_download)
                    isFocusable = true
                    setOnClickListener { onModelSecondary(model, downloaded) }
                })
            }

            modelButtons.addView(container)
        }
    }

    private fun describe(model: ModelInfo): String {
        val active = model.fileName == (prefs.selectedModel ?: ModelStore.ALL_MODELS.first { it.bundled }.fileName)
        return buildString {
            append(model.label).append(" · ").append(model.sizeLabel)
            if (model.bundled) append(" · ").append(getString(R.string.model_bundled))
            if (active) append(" · ").append(getString(R.string.model_in_use))
        }
    }

    private fun onModelSelected(model: ModelInfo) {
        if (!models.fileFor(model).exists()) {
            download(model)
            return
        }
        models.select(model)
        WhisperEngine.release()
        refreshStatus()
        buildModelRows()
    }

    private fun onModelSecondary(model: ModelInfo, downloaded: Boolean) {
        if (downloaded) {
            models.delete(model)
            refreshStatus()
            buildModelRows()
        } else {
            download(model)
        }
    }

    private fun download(model: ModelInfo) {
        statusLine.text = getString(R.string.downloading, model.label, 0)
        scope.launch {
            try {
                models.download(model) { written, total ->
                    val percent = if (total > 0) (written * 100 / total).toInt() else 0
                    ui.post { statusLine.text = getString(R.string.downloading, model.label, percent) }
                }
                models.select(model)
                ui.post {
                    statusLine.text = getString(R.string.status_model_ready, model.label)
                    refreshStatus()
                    buildModelRows()
                }
            } catch (e: Exception) {
                Log.e(TAG, "model download failed", e)
                ui.post { statusLine.text = getString(R.string.error_server) }
            }
        }
    }

    // --- self test ---------------------------------------------------------

    /**
     * Drives the real service through the platform API, so this checks the whole
     * chain: default-recognizer resolution, microphone, decode and delivery.
     */
    private fun startTest() {
        if (!DefaultRecognizer.isZeusDefault(this)) {
            statusLine.text = getString(R.string.error_client)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusLine.text = getString(R.string.error_no_permission)
            return
        }

        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(testListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, prefs.emitPartials)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        testStartedAtMs = android.os.SystemClock.elapsedRealtime()
        testOutput.setText(R.string.engine_listening)
        testButton.setText(R.string.stop_test)
        speech?.startListening(intent)
    }

    private fun stopTest() {
        speech?.stopListening()
    }

    private val testListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            ui.post { testOutput.setText(R.string.engine_listening) }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            ui.post { testOutput.setText(R.string.engine_processing) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.firstResult() ?: return
            ui.post { testOutput.text = getString(R.string.engine_partial, text) }
        }

        override fun onResults(results: Bundle?) {
            val elapsed = (android.os.SystemClock.elapsedRealtime() - testStartedAtMs).toInt()
            val text = results?.firstResult().orEmpty()
            ui.post {
                testOutput.text = if (text.isBlank()) {
                    getString(R.string.status_idle)
                } else {
                    getString(R.string.engine_result, text)
                }
                testButton.setText(R.string.run_test)
                statusLine.text = getString(
                    R.string.engine_timing,
                    String.format(Locale.US, "%.1f", elapsed / 1000.0),
                    String.format(Locale.US, "%.2f", WhisperEngine.lastRtf),
                    elapsed,
                )
            }
        }

        override fun onError(error: Int) {
            ui.post {
                testOutput.text = getString(R.string.engine_error, describeError(error))
                testButton.setText(R.string.run_test)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun describeError(code: Int): String = getString(
        when (code) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.error_busy
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.error_no_permission
            SpeechRecognizer.ERROR_CLIENT -> R.string.error_client
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.error_no_match
            SpeechRecognizer.ERROR_NO_MATCH -> R.string.error_no_match
            else -> R.string.error_server
        },
    )

    private fun Bundle.firstResult(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "ZeusSetup"
        const val REQ_MIC = 1
    }
}