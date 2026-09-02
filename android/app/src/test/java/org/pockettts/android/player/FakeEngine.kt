package org.pockettts.android.player

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * A [SpeechEngine] that produces a fixed number of samples per chunk instantly.
 *
 * Records every chunk it was asked for, which is how the tests below check
 * *where* the reader resumed after a skip.
 */
class FakeEngine(
    override val sampleRate: Int = 24_000,
    private val failOn: String? = null,
) : SpeechEngine {

    val spoken = mutableListOf<String>()
    var voiceId: String? = null
        private set

    /** What each call was conditioned on: null for the voice's own prompt. */
    val conditionedOn = mutableListOf<FloatArray?>()
    private var continuation: FloatArray? = null

    /** Completed by the test to let a synthesis call finish, when it wants control. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun useVoice(voiceId: String) {
        this.voiceId = voiceId
        continuation = null
    }

    override fun continueFrom(audio: FloatArray?) {
        continuation = audio
    }

    override suspend fun synthesize(
        text: String,
        speed: Float,
        onAudio: (FloatArray) -> Boolean,
    ): Boolean {
        // Deliberately not instant. A fake that returns before its caller can
        // look at it hides every ordering bug in the tests that drive it: the
        // reader publishes Speaking one line *before* it asks for a chunk, and
        // an instant fake makes that gap unobservable here and observable in
        // CI. Half a real sentence would be seconds; this is enough to keep
        // the window open without slowing the suite.
        delay(WORK_MILLIS)
        spoken += text
        conditionedOn += continuation
        if (text == failOn) throw IllegalStateException("synthesis exploded")
        gate?.await()
        return onAudio(FloatArray(sampleRate / 10))
    }

    private companion object {
        const val WORK_MILLIS = 50L
    }

    class Factory(private val engine: SpeechEngine) : SpeechEngine.Factory {
        var created = 0
            private set

        override suspend fun create(
            context: Context,
            progress: (Float) -> Unit,
        ): SpeechEngine {
            created++
            return engine
        }
    }
}

/** An [AudioSink] that swallows audio and counts what it was told to do. */
class FakeSink : AudioSink {

    var started = 0
        private set
    var released = 0
        private set
    var drained = 0
        private set
    var samplesWritten = 0
        private set

    private var stopped = false
    override var isPaused: Boolean = false
        private set

    override fun start() { started++ }

    override fun write(samples: FloatArray): Boolean {
        if (stopped) return false
        samplesWritten += samples.size
        return true
    }

    override fun writeSilence(seconds: Float) = !stopped
    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun drain() { drained++ }
    override fun stop() { stopped = true }
    override fun release() { released++ }

    class Factory : AudioSink.Factory {
        val created = mutableListOf<FakeSink>()
        override fun create(sampleRate: Int): AudioSink = FakeSink().also { created += it }
    }
}
