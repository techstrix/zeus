package com.xorbi.zeus

/**
 * Process-wide handle to the loaded Whisper model.
 *
 * Loading a model costs hundreds of milliseconds to seconds; decoding costs
 * milliseconds. So exactly one model stays resident for the life of the process
 * and every session borrows it. [ZeusRecognitionService] is bound and unbound
 * constantly, so a fresh load per session would be felt on every dictation.
 *
 * All native calls are serialised on [lock]: whisper contexts are not safe for
 * concurrent decode, and the platform only ever gives us one session at a time.
 */
object WhisperEngine {

    private val lock = Any()

    /** Set while `whisper_init_from_file_with_params` runs. */
    @Volatile
    private var handle: Long = 0

    @Volatile
    private var loadedPath: String? = null

    /** Last measured real-time factor, for the setup screen. */
    @Volatile
    var lastRtf: Double = 0.0
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isLoaded: Boolean get() = handle != 0L

    val loadedModelPath: String? get() = loadedPath

    val threads: Int
        get() = synchronized(lock) {
            if (handle == 0L) 0 else nativeThreads(handle)
        }

    /**
     * Loads [modelPath] unless it is already the resident model. Safe to call on
     * every session start; throws if the model cannot be loaded so the caller can
     * turn it into a `SpeechRecognizer.ERROR_MODEL_*` code.
     */
    fun ensureLoaded(modelPath: String) {
        synchronized(lock) {
            if (handle != 0L && loadedPath == modelPath) return

            if (handle != 0L) {
                nativeFree(handle)
                handle = 0
                loadedPath = null
            }

            lastError = null
            val fresh = nativeLoadModel(modelPath, resolveThreads())
            if (fresh == 0L) {
                throw IllegalStateException("could not load model: $modelPath")
            }
            handle = fresh
            loadedPath = modelPath
        }
    }

    /** True once a [ensureLoaded] call is known to have succeeded for this model. */
    fun isModelReady(modelPath: String): Boolean =
        synchronized(lock) { handle != 0L && loadedPath == modelPath }

    /**
     * Decodes the first [length] samples of [pcm] (mono 16-bit at 16 kHz).
     *
     * There is deliberately no way to decode a slice of [pcm]. Measured on this
     * engine, a decode costs the same for half a second of audio as for eleven
     * seconds, because every utterance is padded to a 30-second encoder context;
     * so windowed decoding would save no CPU while losing context at the seams.
     * A decode always covers everything captured so far.
     *
     * [initialPrompt] conditions the decoder on text already emitted. It is what
     * lets a caller stabilise output if it ever needs to; the session in this app
     * re-decodes whole utterances and leaves it empty.
     */
    fun transcribe(
        pcm: ShortArray,
        length: Int,
        language: String = "en",
        translate: Boolean = false,
        initialPrompt: String = "",
    ): String = synchronized(lock) {
        if (handle == 0L) throw IllegalStateException("engine not loaded")
        require(length in 0..pcm.size) { "length=$length out of bounds for ${pcm.size}" }

        val text = nativeTranscribe(handle, pcm, length, language, translate, initialPrompt)
        lastRtf = nativeLastRtf(handle)
        text
    }

    fun release() {
        synchronized(lock) {
            if (handle != 0L) {
                nativeFree(handle)
                handle = 0
                loadedPath = null
            }
        }
    }

    /**
     * Four threads is where ggml's synchronisation overhead starts eating the
     * gain on the small core counts TVs have, and past eight there is nothing
     * left to win.
     */
    private fun resolveThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private external fun nativeLoadModel(modelPath: String, threads: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeTranscribe(
        handle: Long,
        pcm: ShortArray,
        length: Int,
        language: String,
        translate: Boolean,
        initialPrompt: String,
    ): String

    private external fun nativeThreads(handle: Long): Int
    private external fun nativeLastRtf(handle: Long): Double

    init {
        System.loadLibrary("zeus")
    }
}