package com.xorbi.zeus

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A Whisper ggml model the app can offer. */
data class ModelInfo(
    val fileName: String,
    val label: String,
    val sizeBytes: Long,
    val url: String,
    val bundled: Boolean,
) {
    val sizeLabel: String
        get() = if (sizeBytes >= 1L shl 30) {
            String.format(java.util.Locale.US, "%.1f GB", sizeBytes / (1L shl 30))
        } else {
            String.format(java.util.Locale.US, "%d MB", sizeBytes / (1L shl 20))
        }
}

/**
 * Owns the model files on disk.
 *
 * The bundled tiny.en is copied out of assets on first use rather than being
 * loaded straight from the APK: whisper needs a real path, and keeping one copy
 * in filesDir means the downloaded and bundled models are interchangeable.
 */
class ModelStore(private val context: Context) {

    private val prefs = Prefs(context)

    val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(model: ModelInfo): File = File(modelsDir, model.fileName)

    /** Models offered on the setup screen. */
    fun catalogue(): List<ModelInfo> = ALL_MODELS.filter { it.bundled || fileFor(it).exists() }

    /**
     * The model in use: the user's selection if it is present on disk, otherwise
     * the bundled one. Falls back to the bundled model rather than failing, so a
     * deleted download cannot leave the app unable to serve recognition.
     */
    fun activeModel(): ModelInfo {
        val selected = prefs.selectedModel
        val byName = ALL_MODELS.firstOrNull { it.fileName == selected }
        if (byName != null && (byName.bundled || fileFor(byName).exists())) return byName
        return ALL_MODELS.first { it.bundled }
    }

    /** True when the active model exists on disk (assets already unpacked). */
    fun isActiveModelReady(): Boolean = fileFor(activeModel()).exists()

    /** Copies the bundled model out of assets. Idempotent and cheap once done. */
    fun unpackBundledModel(model: ModelInfo = activeModel()): File {
        val target = fileFor(model)
        if (target.exists() && target.length() == model.sizeBytes) return target

        Log.i(TAG, "unpacking ${model.fileName} from assets")
        try {
            context.assets.open("models/${model.fileName}").use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
        } catch (e: IOException) {
            Log.e(TAG, "could not unpack ${model.fileName}", e)
            target.delete()
            throw e
        }
        return target
    }

    /**
     * Downloads a model to a temp file and renames it into place, so an
     * interrupted download never leaves a truncated file that looks valid.
     *
     * @param onProgress invoked with bytes written so far and the expected total
     */
    suspend fun download(
        model: ModelInfo,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = fileFor(model)
        val partial = File(modelsDir, "${model.fileName}.part")

        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} for ${model.url}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes

            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }

            if (partial.length() != model.sizeBytes) {
                throw IOException(
                    "size mismatch for ${model.fileName}: got ${partial.length()}, want ${model.sizeBytes}",
                )
            }
            if (!partial.renameTo(target)) throw IOException("could not move ${model.fileName} into place")
            target
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Switches model and drops the resident one so the next session reloads. */
    fun select(model: ModelInfo) {
        prefs.selectedModel = model.fileName
        if (WhisperEngine.loadedModelPath != fileFor(model).absolutePath) {
            WhisperEngine.release()
        }
    }

    /** Deletes a downloaded model. The bundled one cannot be removed. */
    fun delete(model: ModelInfo): Boolean {
        if (model.bundled) return false
        if (fileFor(model).delete()) {
            if (prefs.selectedModel == model.fileName) {
                prefs.selectedModel = null
                WhisperEngine.release()
            }
            return true
        }
        return false
    }

    companion object {
        const val TAG = "ZeusModels"

        private const val HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        val ALL_MODELS: List<ModelInfo> = listOf(
            ModelInfo(
                fileName = "ggml-tiny.en-q5_1.bin",
                label = "tiny.en q5_1",
                sizeBytes = 32_166_155L,
                url = HF + "ggml-tiny.en-q5_1.bin",
                bundled = true,
            ),
            ModelInfo(
                fileName = "ggml-base.en-q5_1.bin",
                label = "base.en q5_1",
                sizeBytes = 59_721_011L,
                url = HF + "ggml-base.en-q5_1.bin",
                bundled = false,
            ),
            ModelInfo(
                fileName = "ggml-small.en-q5_1.bin",
                label = "small.en q5_1",
                sizeBytes = 190_098_681L,
                url = HF + "ggml-small.en-q5_1.bin",
                bundled = false,
            ),
        )
    }
}