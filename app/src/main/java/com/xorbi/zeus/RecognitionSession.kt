package com.xorbi.zeus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One dictation utterance: capture, endpoint, decode, deliver.
 *
 * Threads:
 *  - the capture thread (owned by [AudioCapture]) pushes frames in
 *  - a single-slot decode executor, because a whisper context is not reentrant
 *    and because all [RecognitionListener] traffic has to stay ordered
 *
 * The pacing of decodes is the whole design. Measured with
 * tools/hosttest/scaling.cpp, a whisper_full() call costs roughly the same
 * whether it is given 0.5 s or 11 s of audio, because every utterance is padded
 * to a 30-second encoder context. The number of calls is therefore the only cost
 * that matters, so partials are paced off how long a decode actually took
 * ([decodeIntervalMs]) rather than off a fixed tick, and never queue faster than
 * the device can complete them.
 */
class RecognitionSession(
    private val context: Context,
    private val intent: Intent?,
    private val modelPath: String,
    private val prefs: Prefs,
    private val listener: RecognitionService.Callback,
) {

    private val capture = AudioCapture(::onFrame)
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zeus-decode")
    }

    private val endpointer = Endpointer(silenceHoldMs = silenceHoldMs())

    private val finished = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    /** Growable capture buffer; doubling keeps appends O(n) over an utterance. */
    private var buffer = ShortArray(INITIAL_CAPACITY_SAMPLES)

    /** Samples written to [buffer]. */
    private var filled = 0

    /** First sample of actual speech, found at onset. Everything before is trimmed. */
    private var speechStartSample = 0

    /** Last text actually delivered, so identical partials are not re-sent. */
    private var lastDelivered = String()

    /** Wall duration of the most recent decode; drives partial pacing. */
    private var lastDecodeWallMs = 0L

    /** When the most recent decode was queued. */
    private var lastDecodeQueuedAtMs = 0L

    private var lastLevelAtMs = 0L

    /** Set while a decode is queued, so a burst of frames cannot queue another. */
    private val decodeQueued = AtomicBoolean(false)

    private val language: String = resolveLanguage()

    // --- lifecycle ---------------------------------------------------------

    fun start() {
        if (!started.compareAndSet(false, true)) return

        // EXTRA_AUDIO_SOURCE is API 33. On older platforms the extra is simply
        // absent and getIntExtra returns the -1 default, so this needs no guard.
        @SuppressLint("InlinedApi")
        val requestedSource = intent?.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, -1) ?: -1
        if (requestedSource >= 0) capture.setRequestedSource(requestedSource)

        if (!hasMicPermission()) {
            fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        try {
            WhisperEngine.ensureLoaded(modelPath)
        } catch (e: Exception) {
            Log.e(TAG, "model load failed", e)
            fail(SpeechRecognizer.ERROR_SERVER)
            return
        }

        send { it.readyForSpeech(Bundle.EMPTY) }

        if (!capture.start()) {
            // In practice this means another app already owns the microphone.
            fail(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
    }

    /** Caller asked to stop: hand back whatever has been transcribed. */
    fun stop() {
        if (finished.get()) return
        finishCapture(deliverResults = true, endOfSpeech = false)
    }

    /** Caller cancelled. No callbacks afterwards; silence is the correct response. */
    fun cancel() {
        if (!finished.compareAndSet(false, true)) return
        capture.stop()
        decodeExecutor.shutdownNow()
    }

    private fun hasMicPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // --- capture -----------------------------------------------------------

    private fun onFrame(samples: ShortArray, length: Int, dbfs: Float) {
        if (finished.get()) return

        append(samples, length)
        val now = SystemClock.elapsedRealtime()

        when (endpointer.onFrame(dbfs, frameMs(length), now)) {
            Endpointer.Decision.SPEECH_STARTED -> {
                speechStartSample = maxOf(0, filled - length)
                send { it.beginningOfSpeech() }
            }

            Endpointer.Decision.END_OF_SPEECH -> {
                Log.d(TAG, "endpointer after ${endpointer.speechElapsedMs} ms of speech")
                finishCapture(deliverResults = true, endOfSpeech = true)
                return
            }

            Endpointer.Decision.NONE -> Unit
        }

        emitLevel(dbfs, now)
        maybeQueuePartial(now, dbfs)
    }

    /** RMS drives the caller's recording animation, so throttle it to about 10 Hz. */
    private fun emitLevel(dbfs: Float, now: Long) {
        if (now - lastLevelAtMs < LEVEL_INTERVAL_MS) return
        lastLevelAtMs = now
        send { it.rmsChanged(dbfs) }
    }

    /**
     * Queues a partial when one is due.
     *
     * A pause is the best moment to refresh the transcript: the speaker has
     * finished a phrase, so the text is least likely to be revised. The interval
     * floor keeps a fast device from burning a decode on every short gap.
     */
    private fun maybeQueuePartial(now: Long, dbfs: Float) {
        if (!wantsPartials) return
        if (!endpointer.hasSpeech) return
        if (filled - speechStartSample < MIN_DECODE_SAMPLES) return
        if (decodeQueued.get()) return

        val sinceLast = now - lastDecodeQueuedAtMs
        val paused = dbfs < Endpointer.SILENCE_THRESHOLD_DB
        if (sinceLast < MIN_INTERVAL_MS) return
        if (!paused && sinceLast < decodeIntervalMs()) return

        lastDecodeQueuedAtMs = now
        queuePartial()
    }

    private fun queuePartial() {
        val length = filled - speechStartSample
        decodeQueued.set(true)
        decodeExecutor.execute {
            decodeQueued.set(false)
            if (finished.get()) return@execute

            val startedAt = SystemClock.elapsedRealtime()
            val text = try {
                WhisperEngine.transcribe(
                    pcm = buffer,
                    length = length,
                    language = language,
                )
            } catch (e: Exception) {
                Log.w(TAG, "partial decode failed", e)
                return@execute
            }
            lastDecodeWallMs = SystemClock.elapsedRealtime() - startedAt

            if (text.isBlank() || text == lastDelivered) return@execute
            lastDelivered = text
            send { it.partialResults(resultsBundle(text)) }
        }
    }

    /**
     * How long to wait before the next partial. Never faster than the device has
     * actually been managing, and never slower than [MAX_INTERVAL_MS], so a slow
     * TV quietly shows fewer partials instead of permanently falling behind.
     */
    private fun decodeIntervalMs(): Long {
        val pace = (lastDecodeWallMs * DECODE_HEADROOM).toLong()
        return pace.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    // --- finishing ---------------------------------------------------------

    /**
     * Stops capture and produces the final result: one full decode of the whole
     * utterance. It costs exactly as much as a partial (see class docs), so there
     * is no reason to skip it, and it is the pass whose text the user actually
     * reads.
     */
    private fun finishCapture(deliverResults: Boolean, endOfSpeech: Boolean) {
        val length = filled - speechStartSample
        val worthReporting = endpointer.hasSpeech &&
            endpointer.endedWorthReporting() &&
            length >= MIN_DECODE_SAMPLES

        if (!worthReporting) {
            // Too short to be speech: a door, a cough. Whisper would answer with
            // confident nonsense, so report nothing heard instead.
            capture.stop()
            finishWithEmptyResult()
            return
        }

        if (endOfSpeech) send { it.endOfSpeech() }
        capture.stop()

        if (!deliverResults) {
            finishWithEmptyResult()
            return
        }

        decodeExecutor.execute {
            if (finished.get()) return@execute

            val startedAt = SystemClock.elapsedRealtime()
            val text = try {
                WhisperEngine.transcribe(
                    pcm = buffer,
                    length = length,
                    language = language,
                )
            } catch (e: Exception) {
                Log.w(TAG, "final decode failed", e)
                fail(SpeechRecognizer.ERROR_SERVER)
                return@execute
            }
            lastDecodeWallMs = SystemClock.elapsedRealtime() - startedAt
            val result = text.trim()
            if (result.isEmpty()) {
                finishWithEmptyResult()
            } else {
                send { it.results(resultsBundle(result)) }
                complete()
            }
        }
    }

    private fun finishWithEmptyResult() {
        if (finished.get()) return
        send { it.results(resultsBundle("")) }
        complete()
    }

    private fun fail(code: Int) {
        if (!finished.compareAndSet(false, true)) return
        capture.stop()
        send { it.error(code) }
        decodeExecutor.shutdown()
    }

    private fun complete() {
        if (!finished.compareAndSet(false, true)) return
        decodeExecutor.shutdown()
    }

    private fun send(block: (RecognitionService.Callback) -> Unit) {
        if (decodeExecutor.isShutdown) return
        decodeExecutor.execute { block(listener) }
    }

    // --- helpers -----------------------------------------------------------

    private fun append(samples: ShortArray, length: Int) {
        if (filled + length > buffer.size) {
            var capacity = buffer.size
            while (capacity < filled + length) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(samples, 0, buffer, filled, length)
        filled += length
    }

    private fun frameMs(length: Int): Long =
        length.toLong() * 1000L / AudioCapture.TARGET_RATE

    private fun silenceHoldMs(): Long {
        val requested = intent?.getLongExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            Endpointer.SILENCE_HOLD_MS,
        ) ?: Endpointer.SILENCE_HOLD_MS
        return requested.coerceIn(200L, 5_000L)
    }

    private val wantsPartials: Boolean
        get() = intent?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, prefs.emitPartials)
            ?: prefs.emitPartials

    private fun resolveLanguage(): String {
        val tag = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?: intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
        if (tag.isNullOrBlank()) return DEFAULT_LANGUAGE
        val primary = tag.substringBefore('-').substringBefore('_').lowercase()
        return primary.ifEmpty { DEFAULT_LANGUAGE }
    }

    private fun resultsBundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    companion object {
        const val TAG = "ZeusSession"

        const val DEFAULT_LANGUAGE = "en"

        /** 16 s of headroom over the endpointer's 15 s cap. */
        private const val INITIAL_CAPACITY_SAMPLES = AudioCapture.TARGET_RATE * 16

        private const val LEVEL_INTERVAL_MS = 100L

        /** Below this much speech there is nothing worth decoding. */
        private const val MIN_DECODE_SAMPLES = AudioCapture.TARGET_RATE / 2  // 500 ms

        /** Never queue partials faster than this, whatever the decode cost. */
        private const val MIN_INTERVAL_MS = 700L

        /** Upper bound so a very fast device still refreshes regularly. */
        private const val MAX_INTERVAL_MS = 3_000L

        /** Leave the device this fraction of idle time between decodes. */
        private const val DECODE_HEADROOM = 1.6
    }
}