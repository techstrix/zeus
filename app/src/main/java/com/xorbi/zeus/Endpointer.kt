package com.xorbi.zeus

/**
 * Decides when the user has stopped talking.
 *
 * Energy-based on purpose: it is a few lines, needs no second model, and cannot
 * fail open the way a thresholded neural VAD can. The cost is that a TV playing
 * in the background will hold the endpointer open; whisper.cpp ships a silero VAD
 * (whisper_vad_detect_speech_no_reset) that fixes that and is the obvious upgrade.
 *
 * Pure logic with no Android or audio dependencies so it can be unit tested on
 * the JVM.
 */
class Endpointer(
    /** Level above which we believe speech started. */
    val speechThresholdDb: Float = SPEECH_THRESHOLD_DB,
    /** Level below which we believe the room has gone quiet again. */
    val silenceThresholdDb: Float = SILENCE_THRESHOLD_DB,
    /** How long the level must stay under [silenceThresholdDb] to end the utterance. */
    val silenceHoldMs: Long = SILENCE_HOLD_MS,
    /** Utterances shorter than this are treated as a stray noise burst and discarded. */
    val minSpeechMs: Long = MIN_SPEECH_MS,
    /** Hard cap so a single utterance can never run unbounded. */
    val maxSpeechMs: Long = MAX_SPEECH_MS,
) {

    enum class Decision {
        /** Nothing interesting happened in this frame. */
        NONE,

        /** Speech has just been detected; the caller should emit beginningOfSpeech. */
        SPEECH_STARTED,

        /** The endpointer fired; the caller should emit endOfSpeech and decode. */
        END_OF_SPEECH,
    }

    private var speechStarted = false
    private var speechStartMs = 0L
    private var quietSinceMs = -1L
    private var activeMs = 0L

    /** True once speech has been detected for this utterance. */
    val hasSpeech: Boolean get() = speechStarted

    /** Milliseconds of speech-bearing audio accepted so far. */
    val activeDurationMs: Long get() = activeMs

    private var lastNowMs = 0L

    /**
     * Feeds one captured frame.
     *
     * @param dbfs        frame level in dBFS (0 dBFS is full scale, negative is quieter)
     * @param frameMs     how much audio the frame covers
     * @param nowMs       monotonic clock reading, milliseconds
     * @return what the session should do about this frame
     */
    fun onFrame(dbfs: Float, frameMs: Long, nowMs: Long): Decision {
        lastNowMs = nowMs
        activeMs += frameMs

        if (!speechStarted) {
            if (dbfs >= speechThresholdDb) {
                speechStarted = true
                speechStartMs = nowMs
                quietSinceMs = -1L
                activeMs = 0
                return Decision.SPEECH_STARTED
            }
            // Pre-speech silence only extends the buffer; it is trimmed later.
            return Decision.NONE
        }

        if (dbfs < silenceThresholdDb) {
            if (quietSinceMs < 0L) quietSinceMs = nowMs
            if (nowMs - quietSinceMs >= silenceHoldMs) {
                return Decision.END_OF_SPEECH
            }
        } else {
            quietSinceMs = -1L
        }

        if (nowMs - speechStartMs >= maxSpeechMs) {
            return Decision.END_OF_SPEECH
        }
        return Decision.NONE
    }

    /** Frames since speech onset; this is what [minSpeechMs] is measured against. */
    val speechElapsedMs: Long
        get() = if (!speechStarted) 0L else lastNowMs - speechStartMs

    /**
     * True when the utterance that just ended was long enough to be worth
     * returning. Very short bursts are usually a door slam or a cough and produce
     * confident-looking hallucinations from Whisper.
     */
    fun endedWorthReporting(): Boolean = speechStarted && speechElapsedMs >= minSpeechMs

    fun reset() {
        speechStarted = false
        speechStartMs = 0L
        quietSinceMs = -1L
        activeMs = 0L
        lastNowMs = 0L
    }

    companion object {
        const val SPEECH_THRESHOLD_DB = -35f
        const val SILENCE_THRESHOLD_DB = -40f
        const val SILENCE_HOLD_MS = 900L
        const val MIN_SPEECH_MS = 350L
        const val MAX_SPEECH_MS = 15_000L
    }
}

/** Converts a PCM16 frame to dBFS, clamped at the bottom so silence reports 0 dB rather than -Inf. */
fun rmsDbfs(samples: ShortArray, length: Int): Float {
    if (length <= 0) return SILENCE_FLOOR_DB
    var sum = 0.0
    for (i in 0 until length) {
        val v = samples[i].toDouble()
        sum += v * v
    }
    val rms = Math.sqrt(sum / length)
    if (rms <= 1e-9) return SILENCE_FLOOR_DB
    val db = 20.0 * Math.log10(rms / 32768.0)
    return db.toFloat().coerceAtLeast(SILENCE_FLOOR_DB)
}

const val SILENCE_FLOOR_DB = -90f