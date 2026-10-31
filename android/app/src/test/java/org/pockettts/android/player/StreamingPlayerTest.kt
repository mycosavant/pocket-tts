package org.pockettts.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StreamingPlayer.awaitPlayout], on a simulated clock and playback head. */
class StreamingPlayerTest {

    /** A track playing one frame a millisecond while not paused. */
    private class Track(var paused: Boolean = false) {
        var clock = 0L
        var head = 0L
        var stopped = false

        fun sleep(millis: Long) {
            clock += millis
            if (!paused) head += millis
        }
    }

    private fun drain(track: Track, written: Long, awaitResume: () -> Unit) =
        StreamingPlayer.awaitPlayout(
            written = written,
            head = { track.head },
            paused = { track.paused },
            stopped = { track.stopped },
            awaitResume = awaitResume,
            now = { track.clock },
            sleep = track::sleep,
        )

    @Test
    fun `a drain waits for the head to reach what was written`() {
        val track = Track()
        drain(track, written = 2_000) {}
        assertTrue(track.head >= 2_000)
    }

    @Test
    fun `time paused does not count, so the end of a read is still heard`() {
        val track = Track()
        var pausedFor = 0L
        // Paused at once for five seconds, longer than all that was queued.
        track.paused = true
        drain(track, written = 2_000) {
            track.clock += StreamingPlayer.PAUSE_POLL_MILLIS
            pausedFor += StreamingPlayer.PAUSE_POLL_MILLIS
            if (pausedFor >= 5_000) track.paused = false
        }
        assertEquals(5_000L, pausedFor)
        assertTrue("returned with ${track.head} of 2000 frames played", track.head >= 2_000)
    }

    @Test
    fun `a head that stops short of the end gives up rather than hanging`() {
        val track = Track()
        StreamingPlayer.awaitPlayout(
            written = 2_000,
            head = { minOf(track.head, 1_990) },
            paused = { false },
            stopped = { false },
            awaitResume = {},
            now = { track.clock },
            sleep = track::sleep,
        )
        assertTrue(track.clock < 1_990 + StreamingPlayer.STALL_MILLIS + 2 * StreamingPlayer.DRAIN_POLL_MILLIS)
    }

    @Test
    fun `a stop ends the wait, paused or not`() {
        val track = Track(paused = true)
        drain(track, written = 2_000) { track.stopped = true }
        assertEquals(0L, track.head)
    }
}
