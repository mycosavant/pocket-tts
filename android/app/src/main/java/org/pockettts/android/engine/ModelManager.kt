package org.pockettts.android.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the Pocket TTS weights and voice prompts onto the device.
 *
 * The model is pocket-speak's bundle - the same pinned files the desk CLI
 * installs, 125 MB of int8 ONNX graphs, a tokenizer and a `bundle.json` - so
 * it is fetched on first run and kept in app storage, each file checked
 * against its sha256 before it is kept. A voice is a precomputed embedding of
 * about 6 MB, fetched the first time it is used; the reference wav beside it
 * is only the preview the picker plays.
 */
class ModelManager(private val context: Context) {

    /** Progress of a download, 0..1, or -1 when the total size is unknown. */
    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    private val root: File get() = File(context.filesDir, "pocket-tts")
    val modelDir: File get() = File(root, MODEL_NAME)
    private val voiceDir: File get() = File(root, "voices")
    private val embeddingDir: File get() = File(modelDir, "embeddings")

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

    /** The directory holding [MODEL_FILES], once every one of them is present. */
    data class ModelFiles(val dir: File)

    fun resolveModelOrNull(): ModelFiles? =
        ModelFiles(modelDir).takeIf { MODEL_FILES.all { (name, _) -> File(modelDir, name).isFile } }

    /**
     * Downloads the model files that are missing. Safe to call when installed.
     *
     * One file at a time into a `.part` beside it, checked against its pinned
     * sha256 and renamed into place only if it matches, so a directory that
     * [resolveModelOrNull] accepts holds only verified files. A partial
     * download survives a dropped connection and resumes; a file that finishes
     * with the wrong hash is deleted, because resuming it cannot fix it.
     */
    suspend fun ensureModel(progress: ProgressListener? = null): ModelFiles =
        withContext(Dispatchers.IO) {
            resolveModelOrNull()?.let { return@withContext it }
            modelDir.mkdirs()
            val missing = MODEL_FILES.filter { (name, _) -> !File(modelDir, name).isFile }
            missing.forEachIndexed { index, (name, sha) ->
                val share = 1f / missing.size
                fetchVerified(URL("$BUNDLE_URL/$name"), File(modelDir, name), sha) { fraction ->
                    progress?.onProgress(if (fraction < 0) -1f else (index + fraction) * share)
                }
            }
            resolveModelOrNull() ?: throw IOException("Model files missing from $modelDir after download")
        }

    /**
     * Deletes sherpa-onnx's model, about 200 MB unpacked, once this engine has
     * loaded from its own files. An upgraded install otherwise carries both.
     */
    fun removeRetiredModel() {
        listOf(
            File(root, RETIRED_MODEL_NAME),
            // An interrupted download and unpack of it, up to 98 MB.
            File(root, "$RETIRED_MODEL_NAME.tar.bz2.part"),
            File(root, "$RETIRED_MODEL_NAME.staging"),
        ).filter { it.exists() }.forEach {
            if (it.deleteRecursively()) Log.i(TAG, "Removed the retired model's $it")
        }
    }

    private fun fetchVerified(url: URL, target: File, sha256: String, progress: ProgressListener?) {
        val part = File(target.parentFile, "${target.name}.part")
        download(url, part, progress)
        val actual = sha256Of(part)
        if (actual != sha256) {
            part.delete()
            throw IOException("${target.name}: sha256 $actual, expected $sha256")
        }
        if (!part.renameTo(target)) {
            // Verified but stuck: left in place it would be resumed past its
            // end next time, so it goes and the next attempt starts clean.
            part.delete()
            throw IOException("Could not move ${target.name} into place")
        }
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The embedding for a stock [voice], downloaded and verified on first use.
     */
    suspend fun ensureEmbedding(
        voice: VoiceCatalog.Voice,
        progress: ProgressListener? = null,
    ): File = withContext(Dispatchers.IO) {
        // One download per voice at a time: the reader and the system engine
        // can both ask for an uncached voice at once, and two writers on one
        // `.part` produce a file that is neither.
        embeddingLocks.computeIfAbsent(voice.id) { Mutex() }.withLock {
            embeddingDir.mkdirs()
            val target = File(embeddingDir, "${voice.id}.safetensors")
            // A file on disk is checked once per process before it is
            // trusted. "Any file in the right place counts as cached" is the
            // mistake ensureVoice's doc below records; 6 MB hashes in tens of
            // milliseconds.
            if (target.isFile) {
                if (voice.id in verifiedEmbeddings) return@withLock target
                if (sha256Of(target) == voice.embeddingSha256) {
                    verifiedEmbeddings += voice.id
                    return@withLock target
                }
                Log.w(TAG, "Embedding for ${voice.id} does not match its pin; fetching it again")
                target.delete()
            }
            fetchVerified(URL("$EMBEDDINGS_URL/${voice.id}.safetensors"), target, voice.embeddingSha256, progress)
            verifiedEmbeddings += voice.id
            target
        }
    }

    /**
     * Installing from a local archive took sherpa-onnx's tar.bz2, which the
     * pocket-speak engine cannot read. Refused with that reason until an
     * archive of this bundle is defined; the download is the way in.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun installFromArchive(uri: Uri): ModelFiles =
        throw IOException("Installing from an archive is not supported by this engine yet; use Download")

    /**
     * Downloads a voice prompt if it is not already cached, and returns it.
     *
     * "Cached" means the file on disk is the prompt this voice ships as, not
     * merely a file in the right place with the right name. Those came apart:
     * an import named like a stock voice overwrote that voice's prompt, and
     * because any non-empty file counted as cached, nothing ever fetched the
     * real one again. Every "Alba" from that moment on was read in someone
     * else's voice, silently, on every build since.
     *
     * The size is enough to catch it and costs a stat.
     */
    suspend fun ensureVoice(
        voice: VoiceCatalog.Voice,
        progress: ProgressListener? = null,
    ): File = withContext(Dispatchers.IO) {
        voiceDir.mkdirs()
        val target = File(voiceDir, voice.fileName)
        if (isCached(voice, target)) return@withContext target
        // Not the prompt it claims to be. Deleted rather than resumed: the
        // download below appends to a `.part`, and appending to someone else's
        // wav would produce a longer file that is still not this voice.
        target.delete()

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

    /**
     * Whether [file] is the prompt [voice] ships as.
     *
     * A size rather than a hash: the CDN serves an exact content-length, the
     * files are a megabyte each, and the failure being caught is a wholly
     * different recording, not a corrupted byte.
     */
    fun isCached(voice: VoiceCatalog.Voice, file: File = File(voiceDir, voice.fileName)): Boolean =
        file.isFile && file.length() == voice.bytes

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
     * voice already overwrote that prompt, and is not recoverable as an import
     * because it cannot be told apart from the prompt it replaced. It is left
     * where it is, and [ensureVoice] now notices that it is the wrong size and
     * fetches the real prompt over it - which this comment previously claimed
     * happened, while nothing in the code did it.
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
     * The bundle is 125 MB. Without a `Range` request a drop at 120 MB throws
     * away 120 MB, and on a connection that drops regularly the download never
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
                // 416 on a resume: the part already holds the whole file (the
                // process died between the last byte and the rename). Done;
                // the caller's sha256 check decides whether it is kept.
                if (code == 416 && have > 0) return
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

    companion object {
        private const val TAG = "ModelManager"

        /**
         * pocket-speak's model: Kevin AHM's ONNX export of Kyutai's
         * `english_2026-04`, pinned to a commit, with the sha256s the desk
         * CLI's `install.rs` uses (which are speech-kit's catalog pins).
         */
        const val MODEL_NAME = "english_2026-04"

        /** The sherpa-onnx bundle this app read from until 2026-10-03. */
        private const val RETIRED_MODEL_NAME = "sherpa-onnx-pocket-tts-int8-2026-01-26"
        const val BUNDLE_URL =
            "https://huggingface.co/KevinAHM/pocket-tts-onnx/resolve/58a6d00cf13d239b6748cb0769f35c580a8f606c/onnx/english_2026-04"
        const val EMBEDDINGS_URL =
            "https://huggingface.co/kyutai/pocket-tts-without-voice-cloning/resolve/e041936c75475d350b405bc870bcf7c22da4e9e6/languages/english_2026-04/embeddings"

        /** Per voice, so concurrent ModelManager instances share them. */
        private val embeddingLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private val verifiedEmbeddings: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        val MODEL_FILES: List<Pair<String, String>> = listOf(
            "bundle.json" to "bab643150f437f37df080a710520ff39ed9ebd9a339f8ebdc739f7eddfc28b3f",
            "tokenizer.model" to "d461765ae179566678c93091c5fa6f2984c31bbe990bf1aa62d92c64d91bc3f6",
            "text_conditioner.onnx" to "4ecee995fb69f85c7a7493d11f7b5ee15d9950facc7ab3f5c9c49ef1e03847bb",
            "flow_lm_main_int8.onnx" to "f9bd8106b79a0192c1c43399ab938fb24900a95c1c599870d75a884e99000116",
            "flow_lm_flow_int8.onnx" to "3dd781ee5abee9e195320bf0106bebd6372a852b3b36352524ee78b40554635d",
            "mimi_decoder_int8.onnx" to "3630450a3297a101792a6ac66619ebc70ab916b265e6220c2afaef8b1673f925",
        )
    }
}
