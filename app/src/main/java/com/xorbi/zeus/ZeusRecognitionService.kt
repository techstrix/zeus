package com.xorbi.zeus

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The system-wide speech-to-text engine: Whisper, on the TV, in this process.
 *
 * Any app that calls `SpeechRecognizer.createSpeechRecognizer(context)` is bound
 * here once `voice_recognition_service` points at this service, and its
 * `onStartListening` is handed straight to a [RecognitionSession].
 *
 * Note the service is exported *without* `android:permission`. The
 * `BIND_RECOGNITION_SERVICE` permission is not declared anywhere in AOSP (it is
 * defined by whoever ships the original recognizer, with signature protection),
 * so referencing it would make the permission un-grantable and the platform would
 * not be able to bind us at all. The real gate is
 * `SpeechRecognitionManagerServiceImpl.checkPrivilege`, which only lets the
 * default, on-device or preinstalled service be used; [isAllowed] re-checks the
 * same condition on our side.
 */
class ZeusRecognitionService : RecognitionService() {

    private val prefs by lazy { Prefs(this) }
    private val models by lazy { ModelStore(this) }

    /**
     * Session start-up (unpacking the bundled model, loading it, opening the mic)
     * must not run on the main thread: the bundled model is a 30 MB asset copy and
     * the load takes seconds. Everything the platform expects in response is
     * delivered asynchronously by the session, which is how RecognitionService is
     * designed to be used.
     */
    private val control: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zeus-control")
    }

    @Volatile
    private var session: RecognitionSession? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "created; default recognizer = ${DefaultRecognizer.isZeusDefault(this)}")
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        control.shutdownNow()
        // Deliberately *not* releasing the model here. A bound-only service is
        // destroyed as soon as the last client unbinds, which is after every
        // dictation; releasing here would mean re-loading tens of megabytes
        // before every utterance. The model lives for the process lifetime, which
        // is cheap because an unbound service does not keep the process alive.
        super.onDestroy()
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        if (listener == null) return

        if (!isAllowed()) {
            Log.w(TAG, "rejecting request: Zeus is neither the default recognizer nor preinstalled")
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }

        // Cancel any previous session and clear the reference *before* queueing,
        // or the new session would be dropped by the guard below and the second
        // dictation would silently never start.
        session?.cancel()
        session = null

        control.execute {
            val model = try {
                models.unpackBundledModel()
            } catch (e: Exception) {
                Log.e(TAG, "could not unpack the bundled model", e)
                runCatching { listener.error(SpeechRecognizer.ERROR_SERVER) }
                return@execute
            }

            val started = try {
                RecognitionSession(this, recognizerIntent, model.absolutePath, prefs, listener)
                    .also { it.start() }
            } catch (e: Exception) {
                Log.e(TAG, "session start failed", e)
                runCatching { listener.error(SpeechRecognizer.ERROR_SERVER) }
                return@execute
            }
            session = started
        }
    }

    override fun onStopListening(listener: RecognitionService.Callback?) {
        session?.stop()
    }

    override fun onCancel(listener: RecognitionService.Callback?) {
        session?.cancel()
        session = null
    }

    /**
     * Mirrors the platform's own gate so an unprotected exported service cannot be
     * driven by arbitrary apps: allowed if Zeus is the default recognizer or if it
     * was installed as a system app.
     */
    private fun isAllowed(): Boolean {
        if (DefaultRecognizer.isZeusDefault(this)) return true
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)
    }

    /**
     * API 33+. Reporting accurately lets a caller skip its own "download a model"
     * dance: for Zeus everything is on device and already unpacked.
     */
    @SuppressLint("NewApi")
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        callback: RecognitionService.SupportCallback,
    ) {
        control.execute {
            val ready = runCatching {
                models.unpackBundledModel()
                WhisperEngine.ensureLoaded(models.unpackBundledModel().absolutePath)
                true
            }.getOrDefault(false)

            // Everything runs here, so English is both installed and supported
            // on device, with nothing pending and nothing that needs a network.
            val english = if (ready) listOf(RecognitionSession.DEFAULT_LANGUAGE) else emptyList()
            callback.onSupportResult(
                RecognitionSupport.Builder()
                    .setInstalledOnDeviceLanguages(english)
                    .setSupportedOnDeviceLanguages(english)
                    .setPendingOnDeviceLanguages(emptyList())
                    .setOnlineLanguages(emptyList())
                    .build(),
            )
        }
    }

    companion object {
        const val TAG = "ZeusService"
    }
}