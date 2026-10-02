package com.xorbi.zeus

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Adapts [DictationRunner] to the platform's speech recogniser interface.
 *
 * All the capture/endpoint/decode work lives in [DictationRunner]; this class
 * only translates between `RecognizerIntent` and the runner's options, and
 * between its [Listener] callbacks and `RecognitionService.Callback`.
 */
class RecognitionSession(
    context: Context,
    private val recognizerIntent: android.content.Intent?,
    modelPath: String,
    prefs: Prefs,
    private val callback: RecognitionService.Callback,
) {

    private val runner = DictationRunner(context, modelPath, prefs, object : DictationRunner.Listener {
        override fun onReady() = emit { it.readyForSpeech(Bundle.EMPTY) }

        override fun onBeginningOfSpeech() = emit { it.beginningOfSpeech() }

        override fun onLevel(dbfs: Float) = emit { it.rmsChanged(dbfs) }

        override fun onPartial(text: String) = emit { it.partialResults(resultsBundle(text)) }

        override fun onEndOfSpeech() = emit { it.endOfSpeech() }

        override fun onResult(text: String) = emit { it.results(resultsBundle(text)) }

        override fun onError(code: Int) = emit { it.error(code) }
    })

    fun start() {
        // EXTRA_AUDIO_SOURCE is API 33. On older platforms the extra is simply
        // absent and getIntExtra returns the -1 default, so this needs no guard.
        @SuppressLint("InlinedApi")
        val audioSource =
            recognizerIntent?.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, -1) ?: -1

        val options = DictationRunner.Options().apply {
            languageTag = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
                ?: recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
            partialResults = recognizerIntent?.getBooleanExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS, prefsPartialFallback,
            )
            silenceHoldMs = recognizerIntent?.getLongExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                Endpointer.SILENCE_HOLD_MS,
            )
            if (audioSource >= 0) this.audioSource = audioSource
        }
        runner.start(options)
    }

    fun stop() = runner.stop()

    fun cancel() = runner.cancel()

    private fun emit(block: (RecognitionService.Callback) -> Unit) {
        runCatching { block(callback) }.onFailure { Log.w(TAG, "callback failed", it) }
    }

    private fun resultsBundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private companion object {
        const val TAG = "ZeusSession"

        /** Used when the caller did not express a preference. */
        private const val prefsPartialFallback = true
    }
}