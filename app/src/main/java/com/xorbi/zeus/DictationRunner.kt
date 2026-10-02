package com.xorbi.zeus

import android.content.Context
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One dictation utterance: capture, endpoint, decode, deliver.
 *
 * Deliberately knows nothing about *how* the text reaches the user, so the
 * platform recogniser ([RecognitionSession]) and the TV keyboard mic button
 * ([ui.ZeusInputMethodService]) share this exact implementation rather than
 * maintaining two copies of the tricky parts.
 *
 * Threads:
 *  - the capture thread (owned by [AudioCapture]) pushes frames in
 *  - a single-slot decode executor, because a whisper context is not reentrant
 *    and because all listener traffic has to stay ordered
 *
 * The pacing of decodes is the whole design. Measured with
 * tools/hosttest/scaling.cpp, a whisper_full() call costs roughly the same
 * whether it is given 0.5 s or 11 s of audio, because every utterance is padded
 * to a 30-second encoder context. The number of calls is therefore the only cost
 * that matters, so partials are paced off how long a decode actually took
 * ([decodeIntervalMs]) rather than off a fixed tick, and never queue faster than
 * the device can complete them.
 */
class DictationRunner(
    private val context: Context,
    private val modelPath: String,
    private val prefs: Prefs,
    private val listener: Listener,
) {

    /** Outcomes of one utterance. All calls arrive on an internal worker thread. */
    interface Listener {
        fun onReady()
        fun onBeginningOfSpeech()
        /** ~10 Hz while listening, for driving a recording animation. */
        fun onLevel(dbfs: Float)
        fun onPartial(text: String)

        /** The speaker stopped; the final decode is still running. */
        fun onEndOfSpeech()

        /** Final text. Empty means nothing intelligible was heard. */
        fun onResult(text: String)

        fun onError(code: Int)
    }

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

    private var language: String = DEFAULT_LANGUAGE
    private var emitPartials: Boolean = prefs.emitPartials
    private var requestedAudioSource: Int? = null

    // --- lifecycle ---------------------------------------------------------

    /**
     * Prepares one utterance. Overrides apply to this run only, which is how the
     * two callers express what differs: [RecognitionSession] passes the caller's
     * RecognizerIntent, the keyboard passes its own field's locale.
     */
    class Options {
        var languageTag: String? = null
        var partialResults: Boolean? = null
        var silenceHoldMs: Long? = null
        var audioSource: Int? = null
    }

    fun start(options: Options = Options()) {
        if (!started.compareAndSet(false, true)) return

        language = resolveLanguage(options.languageTag)
        emitPartials = options.partialResults ?: prefs.emitPartials
        requestedAudioSource = options.audioSource
        requestedAudioSource?.let { capture.setRequestedSource(it) }
        options.silenceHoldMs?.let { silenceHoldOverrideMs = it }

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

        post { listener.onReady() }

        if (!capture.start()) {
            // In practice this means another app already owns the microphone.
            fail(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
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

    private var silenceHoldOverrideMs: Long? = null

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
                post { listener.onBeginningOfSpeech() }
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
        post { listener.onLevel(dbfs) }
    }

    /**
     * Queues a partial when one is due.
     *
     * A pause is the best moment to refresh the transcript: the speaker has
     * finished a phrase, so the text is least likely to be revised. The interval
     * floor keeps a fast device from burning a decode on every short gap.
     */
    private fun maybeQueuePartial(now: Long, dbfs: Float) {
        if (!emitPartials) return
        if (!endpointer.hasSpeech) return
        if (filled - speechStartSample < MIN_DECODE_SAMPLES) return
        if (decodeQueued.get()) return

        val sinceLast = now - lastDecodeQueuedAtMs
        if (sinceLast < MIN_INTERVAL_MS) return
        val paused = dbfs < Endpointer.SILENCE_THRESHOLD_DB
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
            post { listener.onPartial(text) }
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

        if (endOfSpeech) post { listener.onEndOfSpeech() }
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
                Log.e(TAG, "final decode failed", e)
                fail(SpeechRecognizer.ERROR_SERVER)
                return@execute
            }
            lastDecodeWallMs = SystemClock.elapsedRealtime() - startedAt

            val result = text.trim()
            if (result.isEmpty()) finishWithEmptyResult() else post { listener.onResult(result) }
            complete()
        }
    }

    private fun finishWithEmptyResult() {
        if (finished.get()) return
        post { listener.onResult("") }
        complete()
    }

    private fun fail(code: Int) {
        if (!finished.compareAndSet(false, true)) return
        capture.stop()
        post { listener.onError(code) }
        decodeExecutor.shutdown()
    }

    private fun complete() {
        if (!finished.compareAndSet(false, true)) return
        decodeExecutor.shutdown()
    }

    /** Keeps every listener callback on one thread and in order. */
    private fun post(block: () -> Unit) {
        if (decodeExecutor.isShutdown) return
        decodeExecutor.execute { block() }
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
        val requested = silenceHoldOverrideMs ?: Endpointer.SILENCE_HOLD_MS
        return requested.coerceIn(200L, 5_000L)
    }

    private fun resolveLanguage(tag: String?): String {
        if (tag.isNullOrBlank()) return DEFAULT_LANGUAGE
        val primary = tag.substringBefore('-').substringBefore('_').lowercase()
        return primary.ifEmpty { DEFAULT_LANGUAGE }
    }

    companion object {
        const val TAG = "ZeusDictation"

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