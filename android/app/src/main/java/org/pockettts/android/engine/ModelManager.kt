package org.pockettts.android.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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

    /**
     * Imported voices, kept apart from the downloaded ones.
     *
     * They shared a directory, and a wav named after a stock voice therefore
     * landed on exactly the path that voice's prompt is cached at - silently
     * replacing it. The import then vanished from the list, because
     * [importedVoices] filters out anything named like a stock voice. One
     * action, two losses, no message.
     */
    private val importedDir: File get() = File(voiceDir, "imported")

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

    fun resolveModelOrNull(): ModelFiles? = resolveModelIn(modelDir)

    private fun resolveModelIn(directory: File): ModelFiles? {
        if (!directory.isDirectory) return null
        val files = directory.walkTopDown().filter { it.isFile }.toList()
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

            // The partial download survives a network failure on purpose: at
            // 98 MB over a connection that drops, deleting it means starting
            // from zero every time, which on a bad line never finishes at all.
            download(URL(MODEL_URL), archive, progress)

            try {
                extractTarBz2(archive, staging)
                // Rename last, so an interrupted download never leaves a
                // half-unpacked directory that looks installed.
                modelDir.deleteRecursively()
                if (!staging.renameTo(modelDir)) {
                    throw IOException("Could not move unpacked model into place")
                }
                archive.delete()
            } catch (error: Throwable) {
                // An archive that will not unpack is not worth resuming - it is
                // truncated, or it is not what we think it is. A slow retry
                // beats a fast failure repeated forever.
                archive.delete()
                throw error
            } finally {
                staging.deleteRecursively()
            }

            resolveModelOrNull() ?: throw IOException(
                "Model bundle unpacked but the expected ONNX files were not found in $modelDir",
            )
        }

    /**
     * Installs the model from a bundle already on the device.
     *
     * The download is 98 MB and the app's whole point is that it works offline,
     * so requiring a network round trip to get started is a poor first
     * impression - and after an uninstall that discarded the model, an
     * infuriating one. Anyone who has the release archive on a laptop can copy
     * it across and point at it here.
     *
     * The same tar.bz2 as the download, streamed straight from the content URI
     * rather than copied to a temporary file first: the unpacked model is
     * already ~200 MB and there is no reason to want another 98 MB alongside it.
     */
    suspend fun installFromArchive(uri: Uri): ModelFiles = withContext(Dispatchers.IO) {
        root.mkdirs()
        val staging = File(root, "$MODEL_NAME.staging")
        staging.deleteRecursively()
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not read that file")
            stream.use { extractTarBz2(it, staging) }

            // Checked before anything is replaced: pointing at the wrong
            // archive should leave a working install working.
            if (resolveModelIn(staging) == null) {
                throw IOException("That archive does not contain a Pocket TTS model")
            }
            modelDir.deleteRecursively()
            if (!staging.renameTo(modelDir)) {
                throw IOException("Could not move the unpacked model into place")
            }
        } finally {
            staging.deleteRecursively()
        }

        resolveModelOrNull() ?: throw IOException("The model unpacked but its files were not found")
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

    /**
     * Copies a user-supplied wav in under [name], or the nearest free variant.
     *
     * A name that collides with a stock voice, or with an earlier import, is
     * suffixed rather than allowed to overwrite: a recorded voice cannot be
     * fetched again, so losing one to a name clash is not a recoverable
     * mistake.
     *
     * @return the stored file, whose name without its extension is the voice id.
     */
    fun importVoice(name: String, bytes: ByteArray): File {
        importedDir.mkdirs()
        // Validate before storing: a file that turns out not to be readable PCM
        // should fail at import, not halfway through the first sentence.
        WavReader.read(bytes)
        val target = File(importedDir, "${freeVoiceId(name)}.wav")
        target.writeBytes(bytes)
        return target
    }

    private fun freeVoiceId(name: String): String {
        fun taken(id: String) =
            VoiceCatalog.byId(id) != null || File(importedDir, "$id.wav").exists()

        if (!taken(name)) return name
        var n = 2
        while (taken("$name-$n")) n++
        return "$name-$n"
    }

    fun cachedVoice(voice: VoiceCatalog.Voice): File? =
        File(voiceDir, voice.fileName).takeIf { it.isFile && it.length() > 0 }

    /** Where the wav for [id] lives, whether it is a stock voice or an imported one. */
    fun voiceFile(id: String): File {
        migrateImportedVoices()
        val imported = File(importedDir, "$id.wav")
        return if (imported.isFile) imported else File(voiceDir, "$id.wav")
    }

    fun importedVoices(): List<File> {
        migrateImportedVoices()
        return importedDir.listFiles()
            ?.filter { it.isFile && it.extension == "wav" }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Moves voices imported by an older build into their own directory.
     *
     * Anything in the voice directory that is not named after a stock voice was
     * an import, and is the user's own. One that *was* named after a stock
     * voice already overwrote that prompt and cannot be told apart from it any
     * more; that one is left where it is and will be replaced by a fresh
     * download.
     */
    private fun migrateImportedVoices() {
        val stock = VoiceCatalog.voices.map { it.fileName }.toSet()
        val strays = voiceDir.listFiles()
            ?.filter { it.isFile && it.extension == "wav" && it.name !in stock }
            ?: return
        if (strays.isEmpty()) return

        importedDir.mkdirs()
        strays.forEach { file ->
            val target = File(importedDir, file.name)
            if (!target.exists()) file.renameTo(target)
        }
    }

    /**
     * Fetches [url] into [target], continuing an earlier attempt if there is one.
     *
     * The bundle is 98 MB. Without a `Range` request a drop at 90 MB throws
     * away 90 MB, and on a connection that drops regularly the download never
     * completes at all - each attempt simply gets a different distance through
     * the same first stretch.
     */
    private fun download(url: URL, target: File, progress: ProgressListener?) {
        var current = url
        var redirects = 0
        while (true) {
            val have = if (target.isFile) target.length() else 0L
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "pocket-tts-android")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
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
                // 206 means the server honoured the range and is sending the
                // rest. 200 means it ignored it and is sending the whole file,
                // so whatever was already there has to go.
                val resuming = code == HttpURLConnection.HTTP_PARTIAL && have > 0
                if (code != HttpURLConnection.HTTP_OK && !resuming) {
                    throw IOException("HTTP $code fetching $current")
                }

                val alreadyHave = if (resuming) have else 0L
                val total = connection.contentLengthLong.let {
                    if (it > 0) it + alreadyHave else it
                }
                target.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(target, resuming).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var written = alreadyHave
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

    private fun extractTarBz2(archive: File, destination: File) =
        archive.inputStream().use { extractTarBz2(it, destination) }

    private fun extractTarBz2(archive: InputStream, destination: File) {
        destination.mkdirs()
        val canonicalDestination = destination.canonicalFile
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive), true),
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
