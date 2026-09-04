package org.pockettts.android.debug

import java.util.ArrayDeque

/**
 * Which voice actually spoke, and what it was given to speak with.
 *
 * There are several separate ways for the wrong person to come out of the
 * speaker, and from the outside they are indistinguishable: the selected voice
 * failing to resolve and falling back, a stock prompt on disk that is not the
 * voice it is named after, a client of the system engine sending a voice id it
 * cached weeks ago, and the model simply drawing a different speaker out of the
 * right prompt. Each needs a different fix and all of them sound like "it used
 * the wrong voice".
 *
 * Nothing here is inferable from a recording, and the phone this runs on has no
 * logcat within reach. So each generation records what it was actually handed,
 * and the Timings screen shows it - the same route the exit reporter takes, for
 * the same reason.
 *
 * Bounded, because a long read is hundreds of lines and only the recent ones
 * answer anything.
 */
object VoiceTrace {

    private const val MAX_LINES = 60

    private val lines = ArrayDeque<String>()
    private var chunk = 0

    /** Records who was asked for and who was found. */
    @Synchronized
    fun resolved(
        caller: String,
        requested: String,
        resolved: String,
        promptBytes: Long,
        expectedBytes: Long,
    ) {
        val size = when {
            promptBytes <= 0 -> "prompt file missing"
            expectedBytes <= 0 -> "$promptBytes bytes, none expected"
            promptBytes == expectedBytes -> "$promptBytes bytes as expected"
            // The one that matters: a stock voice whose file is not the file
            // that voice ships as, which is a different person, permanently.
            else -> "$promptBytes bytes, EXPECTED $expectedBytes"
        }
        val fellBack = if (requested == resolved) "" else "  FELL BACK"
        chunk = 0
        add("[$caller] asked for $requested, got $resolved$fellBack ($size)")
    }

    /** Records the conditioning one generation was given. */
    @Synchronized
    fun generated(
        voiceId: String,
        promptSamples: Int,
        promptRate: Int,
        promptHash: Int,
        temperature: Float,
        seed: Int,
    ) {
        val seconds = if (promptRate > 0) promptSamples.toFloat() / promptRate else 0f
        add(
            "[chunk ${chunk++}] $voiceId prompt=%.2fs hash=%08x temp=%.2f seed=%s"
                .format(seconds, promptHash, temperature, if (seed < 0) "random" else "$seed"),
        )
    }

    @Synchronized
    fun clear() {
        lines.clear()
        chunk = 0
    }

    @Synchronized
    fun report(): String =
        if (lines.isEmpty()) "no reads yet" else lines.joinToString("\n")

    private fun add(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }
}
