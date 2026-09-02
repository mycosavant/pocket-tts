package org.pockettts.android.player

import android.content.Context
import kotlinx.coroutines.CompletableDeferred

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

    /** Completed by the test to let a synthesis call finish, when it wants control. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun useVoice(voiceId: String) {
        this.voiceId = voiceId
    }

    override suspend fun synthesize(
        text: String,
        speed: Float,
        onAudio: (FloatArray) -> Boolean,
    ): Boolean {
        spoken += text
        if (text == failOn) throw IllegalStateException("synthesis exploded")
        gate?.await()
        return onAudio(FloatArray(sampleRate / 10))
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
