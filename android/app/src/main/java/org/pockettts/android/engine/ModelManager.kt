package org.pockettts.android.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the Pocket TTS weights and voice prompts onto the device.
 *
 * The int8 model bundle is a 98 MB download that unpacks to about 200 MB,
 * which is more than anyone wants inside an APK, so it is fetched on first run
 * and kept in app storage. Voice prompts are a few hundred kilobytes each and
 * are fetched lazily, the first time a voice is actually used.
 */
class ModelManager(private val context: Context) {

    /** Progress of a download, 0..1, or -1 when the total size is unknown. */
    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    private val root: File get() = File(context.filesDir, "pocket-tts")
    val modelDir: File get() = File(root, MODEL_NAME)
    private val voiceDir: File get() = File(root, "voices")

    val isModelInstalled: Boolean get() = resolveModelOrNull() != null

    /**
     * The five ONNX graphs and two JSON files sherpa-onnx needs. Resolved by
     * suffix rather than by exact path so that a bundle with a different
     * directory layout, or int8 variants of only some graphs, still works.
     */
    data class ModelFiles(
        val lmFlow: File,
        val lmMain: File,
        val encoder: File,
        val decoder: File,
        val textConditioner: File,
        val vocabJson: File,
        val tokenScoresJson: File,
    )

    fun resolveModelOrNull(): ModelFiles? {
        if (!modelDir.isDirectory) return null
        val files = modelDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return null

        fun pick(vararg stems: String): File? {
            // Prefer an int8 graph when both are present: it is a third of the
            // size and materially faster on a phone CPU.
            val candidates = files.filter { file ->
                val name = file.name.lowercase()
                (name.endsWith(".onnx") || name.endsWith(".json")) &&
                    stems.any { name.startsWith(it) || name.contains(it) }
            }
            return candidates.firstOrNull { it.name.contains("int8") } ?: candidates.firstOrNull()
        }

        val lmFlow = pick("lm_flow", "flow_lm") ?: return null
        val lmMain = pick("lm_main", "flow_lm_main") ?: return null
        val encoder = pick("encoder", "mimi_encoder") ?: return null
        val decoder = pick("decoder", "mimi_decoder") ?: return null
        val textConditioner = pick("text_conditioner") ?: return null
        val vocab = files.firstOrNull { it.name.lowercase().startsWith("vocab") } ?: return null
        val scores = files.firstOrNull { it.name.lowercase().contains("token_scores") } ?: return null

        return ModelFiles(lmFlow, lmMain, encoder, decoder, textConditioner, vocab, scores)
    }

    /** Downloads and unpacks the model bundle. Safe to call when already installed. */
    suspend fun ensureModel(progress: ProgressListener? = null): ModelFiles =
        withContext(Dispatchers.IO) {
            resolveModelOrNull()?.let { return@withContext it }

            root.mkdirs()
            val archive = File(root, "$MODEL_NAME.tar.bz2.part")
            val staging = File(root, "$MODEL_NAME.staging")
            staging.deleteRecursively()

            try {
                download(URL(MODEL_URL), archive, progress)
                extractTarBz2(archive, staging)
                // Rename last, so an interrupted download never leaves a
                // half-unpacked directory that looks installed.
                modelDir.deleteRecursively()
                if (!staging.renameTo(modelDir)) {
                    throw IOException("Could not move unpacked model into place")
                }
            } finally {
                archive.delete()
                staging.deleteRecursively()
            }

            resolveModelOrNull() ?: throw IOException(
                "Model bundle unpacked but the expected ONNX files were not found in $modelDir",
            )
        }

    /** Downloads a voice prompt if it is not already cached, and returns it. */
    suspend fun ensureVoice(
        voice: VoiceCatalog.Voice,
        progress: ProgressListener? = null,
    ): File = withContext(Dispatchers.IO) {
        voiceDir.mkdirs()
        val target = File(voiceDir, voice.fileName)
        if (target.isFile && target.length() > 0) return@withContext target

        val temp = File(voiceDir, "${voice.fileName}.part")
        try {
            download(URL(voice.url), temp, progress)
            if (!temp.renameTo(target)) throw IOException("Could not save voice ${voice.id}")
        } finally {
            temp.delete()
        }
        target
    }

    /** Copies a user-supplied wav into the voice cache under [id]. */
    fun importVoice(id: String, bytes: ByteArray): File {
        voiceDir.mkdirs()
        // Validate before storing: a file that turns out not to be readable PCM
        // should fail at import, not halfway through the first sentence.
        WavReader.read(bytes)
        val target = File(voiceDir, "$id.wav")
        target.writeBytes(bytes)
        return target
    }

    fun cachedVoice(voice: VoiceCatalog.Voice): File? =
        File(voiceDir, voice.fileName).takeIf { it.isFile && it.length() > 0 }

    /** Where the wav for [id] lives, whether it is a stock voice or an imported one. */
    fun voiceFile(id: String): File = File(voiceDir, "$id.wav")

    fun importedVoices(): List<File> {
        val stock = VoiceCatalog.voices.map { it.fileName }.toSet()
        return voiceDir.listFiles()
            ?.filter { it.isFile && it.extension == "wav" && it.name !in stock }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun download(url: URL, target: File, progress: ProgressListener?) {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "pocket-tts-android")
            }
            try {
                val code = connection.responseCode
                // Hugging Face and GitHub releases both redirect to a CDN, and
                // HttpURLConnection will not follow a redirect across protocols.
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect with no Location from $current")
                    if (++redirects > 5) throw IOException("Too many redirects fetching $url")
                    current = URL(current, location)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $code fetching $current")
                }

                val total = connection.contentLengthLong
                target.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var written = 0L
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (progress != null && total > 0) {
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    progress.onProgress(percent / 100f)
                                }
                            }
                        }
                        if (progress != null && total <= 0) progress.onProgress(-1f)
                    }
                }
            } finally {
                connection.disconnect()
            }
            return
        }
    }

    private fun extractTarBz2(archive: File, destination: File) {
        destination.mkdirs()
        val canonicalDestination = destination.canonicalFile
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()), true),
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = File(destination, entry.name).canonicalFile
                // Reject paths that escape the destination: the archive is
                // fetched over the network and is not ours to trust.
                if (!target.path.startsWith(canonicalDestination.path + File.separator)) {
                    Log.w(TAG, "Skipping archive entry outside destination: ${entry.name}")
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it, DEFAULT_BUFFER_SIZE) }
            }
        }
    }

    companion object {
        private const val TAG = "ModelManager"

        /**
         * The int8 English bundle from sherpa-onnx. Pocket TTS itself is
         * multilingual, but this is the only Pocket bundle sherpa-onnx
         * currently publishes.
         */
        const val MODEL_NAME = "sherpa-onnx-pocket-tts-int8-2026-01-26"
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_NAME.tar.bz2"
    }
}
