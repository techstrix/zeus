package com.xorbi.zeus

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone capture at Whisper's native 16 kHz, mono, PCM16.
 *
 * AudioRecord does the delivering; this class only owns the recorder, the read
 * loop and the audio-source fallback chain, and hands raw frames to a listener.
 */
class AudioCapture(
    private val onFrame: (samples: ShortArray, length: Int, dbfs: Float) -> Unit,
) {

    private val running = AtomicBoolean(false)

    private var recorder: AudioRecord? = null
    private var readThread: Thread? = null

    /** The rate frames are captured at; always [TARGET_RATE] after [start] succeeds. */
    var captureRate: Int = TARGET_RATE
        private set

    var sourceUsed: Int = -1
        private set

    /** [RecognizerIntent.EXTRA_AUDIO_SOURCE] when the caller supplied one. */
    private var requestedSource: Int? = null

    fun setRequestedSource(source: Int?) {
        requestedSource = source
    }

    /**
     * Opens the microphone and starts pumping frames.
     *
     * @return true if capture started. False means the mic is unavailable, which
     *         in practice means another app already holds it — Android grants mic
     *         access to one app at a time.
     */
    @SuppressLint("MissingPermission")  // RECORD_AUDIO is checked before we get here.
    fun start(): Boolean {
        check(running.compareAndSet(false, true)) { "already running" }

        val created = openRecorder() ?: run {
            running.set(false)
            return false
        }
        recorder = created.recorder
        captureRate = created.rate
        sourceUsed = created.source

        try {
            created.recorder.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed", e)
            releaseRecorder()
            running.set(false)
            return false
        }

        readThread = Thread({ readLoop(created.recorder, created.rate) }, "zeus-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /** Stops capture and joins the read thread. Safe to call when not running. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            recorder?.stop()
        } catch (e: IllegalStateException) {
            // Already stopped; nothing to do.
        }
        // The endpointer fires from inside the read loop, so finishing a session
        // calls stop() from that very thread. Joining ourselves would just burn
        // the timeout, so skip it; `running` is already false, which is what
        // makes the loop exit on its next iteration.
        if (readThread != null && readThread !== Thread.currentThread()) {
            readThread?.join(500)
        }
        readThread = null
        releaseRecorder()
    }

    /**
     * Frames are handed over the array owned by the read loop, which is reused
     * the moment [onFrame] returns. Consumers must copy anything they keep.
     */
    private fun readLoop(rec: AudioRecord, rate: Int) {
        val frame = ShortArray(FRAMES_PER_READ)
        val raw = ShortArray(FRAMES_PER_READ)
        val resample = if (rate != TARGET_RATE) Resampler(rate, TARGET_RATE) else null

        while (running.get()) {
            val read = rec.read(raw, 0, raw.size)
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE ||
                    read == AudioRecord.ERROR_DEAD_OBJECT
                ) {
                    Log.w(TAG, "AudioRecord.read failed: $read")
                    return
                }
                continue
            }

            val samples: ShortArray
            val length: Int
            if (resample == null) {
                samples = raw
                length = read
            } else {
                length = resample.resample(raw, read, frame)
                samples = frame
            }
            if (length <= 0) continue

            onFrame(samples, length, rmsDbfs(samples, length))
        }
    }

    private data class Opened(val recorder: AudioRecord, val source: Int, val rate: Int)

    private fun openRecorder(): Opened? {
        // The caller's EXTRA_AUDIO_SOURCE first if it supplied one, then the
        // fallback chain, then everything else, without duplicates.
        val preferred = requestedSource?.takeIf { it >= 0 }
        val candidates = buildList {
            preferred?.let { add(it) }
            addAll(FALLBACK_SOURCES)
        }.distinct()

        for (source in candidates) {
            // Prefer 16 kHz: Whisper consumes it directly, so no resampling.
            openRecorderAt(source, TARGET_RATE)?.let { return it }
            // Not every TV audio HAL will serve 16 kHz. 48 kHz is the other
            // universal rate, and resampling from it is cheap.
            openRecorderAt(source, FALLBACK_RATE)?.let {
                Log.i(TAG, "falling back to ${it.rate} Hz capture")
                return it
            }
        }
        Log.e(TAG, "no usable AudioRecord configuration")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openRecorderAt(source: Int, rate: Int): Opened? {
        val minBytes = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return null

        // Several buffers deep so a slow decode does not overrun the capture ring.
        val bufferBytes = maxOf(minBytes * 4, FRAMES_PER_READ * 2 * 4)

        val rec = try {
            @Suppress("DEPRECATION")
            AudioRecord(
                source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "source $source unsupported", e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "source $source rejected", e)
            return null
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return Opened(rec, source, rate)
    }

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    companion object {
        const val TAG = "ZeusCapture"

        const val TARGET_RATE = 16_000
        private const val FALLBACK_RATE = 48_000

        /** 256 ms per frame: coarse enough to be cheap, fine enough to see silence gaps. */
        const val FRAMES_PER_READ = 4_096

        private val FALLBACK_SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )
    }
}

/**
 * Decimation resampler for the fallback capture rate (48 kHz -> 16 kHz).
 *
 * Carries the fractional phase across buffer boundaries so the output stays
 * aligned over a long utterance; without that the timing drifts by up to a
 * sample per buffer and speech develops a metallic edge.
 */
class Resampler(fromRate: Int, toRate: Int) {

    /** Input samples consumed per output sample. */
    private val step = fromRate.toDouble() / toRate

    /** Fractional progress through the current step, carried between buffers. */
    private var phase = 0.0

    /** Fills [out] with resampled 16 kHz samples and returns how many were written. */
    fun resample(input: ShortArray, length: Int, out: ShortArray): Int {
        // Needs a pair to interpolate between.
        if (length < 2) {
            phase = 0.0
            return 0
        }

        var outIndex = 0
        var i = 0
        var f = phase

        while (i + 1 < length && outIndex < out.size) {
            val a = input[i].toFloat()
            val b = input[i + 1].toFloat()
            out[outIndex++] = (a + (b - a) * f.toFloat()).toInt().toShort()

            f += step
            val advance = f.toInt()
            i += advance
            f -= advance
        }

        phase = f
        return outIndex
    }
}