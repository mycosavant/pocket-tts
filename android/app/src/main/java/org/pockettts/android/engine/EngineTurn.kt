package org.pockettts.android.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * Whose turn it is to use the one engine in the process.
 *
 * There is a single model, and two things drive it: the in-app reader, and the
 * system text-to-speech service that other apps call. `PocketTts.synthesize`
 * serialises them, but it does so *per chunk* - so with both active they took
 * turns sentence by sentence, and the result was one passage read alternately
 * in two different voices. Neither side knew the other existed.
 *
 * Serialising whole utterances instead would fix the alternation and replace it
 * with a wait: an app asking for speech through Select to Speak would hang until
 * a whole document finished reading, which looks broken from the other side.
 *
 * So the rule is that the most recent request wins. A caller takes a turn before
 * it starts and checks it as it goes; finding the turn has moved on means
 * somebody asked for speech more recently, and the polite thing - the only thing
 * that produces intelligible audio - is to stop.
 */
object EngineTurn {

    private val turn = AtomicLong(0)

    /** Claims the engine, standing down whoever held it. */
    fun take(): Long = turn.incrementAndGet()

    /** True when [held] is no longer the current turn. */
    fun superseded(held: Long): Boolean = turn.get() != held

    /** For tests: forgets any turn taken. */
    fun resetForTesting() {
        turn.set(0)
    }
}
