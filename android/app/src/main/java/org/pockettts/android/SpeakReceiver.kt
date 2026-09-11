package org.pockettts.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.pockettts.android.engine.Settings
import org.pockettts.android.player.PlaybackService
import org.pockettts.android.player.Reader
import java.io.File

/**
 * Reads text sent by another process, with no window and no screen.
 *
 * The case this exists for is a coding agent on the same phone: a reply is
 * finished, and the way to hear it should not be to copy it, switch apps, paste
 * it and tap. One broadcast and it reads, with the same transport controls in
 * the notification and the lock screen as any other read - because it takes the
 * same route through [Reader] and [PlaybackService] that the floating window
 * takes, rather than a second, quieter one of its own.
 *
 * [ReadAloudActivity][org.pockettts.android.ui.ReadAloudActivity] already
 * accepts an intent and does all of this. What it cannot do is happen with the
 * screen off: an activity start needs a foreground caller, so the read is only
 * ever as headless as whoever asked for it. A receiver has no such requirement,
 * which is the whole of the difference and the only reason this is here.
 *
 * ### Extras
 *
 * | extra    | type    |                                                      |
 * |----------|---------|------------------------------------------------------|
 * | `text`   | String  | the Markdown to read                                 |
 * | `path`   | String  | a UTF-8 file to read instead of `text`               |
 * | `append` | Boolean | queue behind the current read instead of replacing it |
 *
 * ```
 * am broadcast -n org.pockettts.android/.SpeakReceiver --es text "Receiver check."
 * ```
 *
 * ### What this does not do
 *
 * It never starts an activity. That is the point of it: a read asked for with
 * the phone in a pocket must not put a window in front of whatever the user
 * unlocks to later, and Android would refuse the start from the background
 * anyway.
 *
 * It does not strip Markdown itself either. [Reader] does that, through
 * [org.pockettts.android.speech.MarkdownSpeech], exactly as it does for the
 * scratchpad and the selection window - so what an agent's reply sounds like
 * here is what it sounds like everywhere else in the app, including the next
 * time that stripper is improved.
 */
class SpeakReceiver : BroadcastReceiver() {

    /**
     * What one broadcast did, so a test can assert on the decision rather than
     * on its downstream effects.
     *
     * The blank case in particular is invisible from outside: "did not read"
     * and "read nothing" look identical from the reader's state, and only one
     * of them is correct.
     */
    sealed interface Outcome {

        /** Handed to [Reader] as [utterance]. */
        data class Reading(val utterance: Long, val queued: Boolean) : Outcome

        /** Nothing was read, and why not. */
        data class Ignored(val reason: String) : Outcome
    }

    override fun onReceive(context: Context, intent: Intent) {
        // The read outlives this call by minutes; goAsync only has to cover
        // reading the extras and handing them over. The foreground service is
        // what keeps the process alive after that.
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                handle(appContext, intent)
            } finally {
                pending?.finish()
            }
        }
    }

    /**
     * Reads [intent]'s extras and starts the read they describe.
     *
     * Touches the filesystem, so it belongs on an IO dispatcher.
     */
    @VisibleForTesting
    internal fun handle(context: Context, intent: Intent): Outcome {
        val path = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        val inline = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val append = intent.getBooleanExtra(EXTRA_APPEND, false)

        val text = when {
            // The file wins. A caller that sends both is a caller whose text
            // did not fit, and the extra that did not fit is the one to ignore.
            path.isNotEmpty() -> {
                val file = File(path)
                when {
                    !file.isFile -> return ignore("no file at $path")
                    file.length() > MAX_FILE_BYTES ->
                        return ignore("$path is larger than ${MAX_FILE_BYTES / KB} KB")

                    else -> runCatching { file.readText() }
                        .getOrElse { return ignore("could not read $path: ${it.message}") }
                }
            }

            // Extras ride a Binder transaction, which is a fixed buffer shared
            // with everything else in flight. Past this size the failure is the
            // sender's TransactionTooLargeException, or a broadcast that simply
            // never arrives - neither of which says what went wrong. Refusing
            // at a stated size does.
            tooLargeToSendInline(inline) ->
                return ignore("text is larger than ${MAX_INLINE_BYTES / KB} KB; send a path instead")

            else -> inline
        }

        // A no-op, not an empty read. Reading nothing would still take the
        // engine, start a foreground service, claim audio focus and put a
        // notification up to say nothing at all - and a stray broadcast would
        // silence the passage already playing. The system engine refuses blank
        // input for the same reason; see PocketTtsService.onSynthesizeText.
        if (text.isBlank()) return ignore("nothing to read")

        val markdown = Settings(context).treatSelectionAsMarkdown
        val utterance = if (append) {
            Reader.enqueue(context, text, treatAsMarkdown = markdown, source = Reader.Source.Agent)
        } else {
            Reader.speak(context, text, treatAsMarkdown = markdown, source = Reader.Source.Agent)
        }
        PlaybackService.start(context)

        // The only account of this read anybody gets. There is no window, so
        // an agent read that goes wrong is a phone that stayed quiet, and
        // `logcat -s SpeakReceiver Reader PocketTts AudioFocus` is the whole
        // of the diagnosis.
        Log.i(
            TAG,
            "Utterance $utterance: ${text.length} characters" +
                (if (append) ", queued" else "") +
                (if (path.isNotEmpty()) " from $path" else ""),
        )
        return Outcome.Reading(utterance, queued = append)
    }

    private fun ignore(reason: String): Outcome {
        Log.w(TAG, "Ignored: $reason")
        return Outcome.Ignored(reason)
    }

    companion object {

        private const val TAG = "SpeakReceiver"

        private const val KB = 1024

        const val EXTRA_TEXT = "text"
        const val EXTRA_PATH = "path"
        const val EXTRA_APPEND = "append"

        /**
         * The most text that may be sent in the intent itself.
         *
         * A guard rail rather than a limit anyone should meet: an agent's reply
         * is a few kilobytes, and 64 KB is about eleven thousand words, which
         * is two hours of speech.
         */
        const val MAX_INLINE_BYTES = 64 * KB

        /**
         * The most that may be read from [EXTRA_PATH].
         *
         * A path is read with this app's own permissions, so the size has to be
         * bounded by something other than the caller's good intentions.
         */
        const val MAX_FILE_BYTES = 1024L * KB

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Measured in UTF-8 bytes, because that is what "64 KB" means to
         * whoever is writing the sending script. The length check first is not
         * an approximation of it - a UTF-16 unit is never fewer than one UTF-8
         * byte - it just avoids encoding a string already known to be too big.
         */
        private fun tooLargeToSendInline(text: String): Boolean =
            text.length > MAX_INLINE_BYTES ||
                text.toByteArray(Charsets.UTF_8).size > MAX_INLINE_BYTES
    }
}
