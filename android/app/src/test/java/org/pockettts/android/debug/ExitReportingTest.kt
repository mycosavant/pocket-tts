package org.pockettts.android.debug

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an exit record earns a dialog.
 *
 * This is the whole decision, and it is a function of four numbers, so it is
 * worth pinning on its own rather than through a real `ApplicationExitInfo`
 * that only a device can produce.
 *
 * The case that motivated it: an S25 Ultra reported `LOW_MEMORY`, importance
 * 400, rss 73 MB, status 0 - Android reclaiming a cached background process,
 * which is the memory manager working exactly as designed. The app announced
 * it as "Pocket TTS closed unexpectedly", and announced it again on every
 * return to the main screen, because nothing recorded that it had been shown.
 */
class ExitReportingTest {

    private val cached = RunningAppProcessInfo.IMPORTANCE_CACHED
    private val foreground = RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    private val foregroundService = RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

    private fun worthReporting(reason: Int, importance: Int, at: Long = 100L, seen: Long = 0L) =
        ExitReasons.isWorthReporting(reason, importance, at, seen)

    @Test
    fun `a cached process trimmed for memory is not a crash`() {
        assertFalse(worthReporting(ApplicationExitInfo.REASON_LOW_MEMORY, cached))
    }

    @Test
    fun `a task killer signalling a cached process is not a crash`() {
        // Several OEM "battery optimisers" report their housekeeping this way.
        assertFalse(worthReporting(ApplicationExitInfo.REASON_SIGNALED, cached))
    }

    @Test
    fun `being killed for memory while reading aloud is worth saying`() {
        assertTrue(worthReporting(ApplicationExitInfo.REASON_LOW_MEMORY, foreground))
        assertTrue(worthReporting(ApplicationExitInfo.REASON_LOW_MEMORY, foregroundService))
    }

    @Test
    fun `a crash is reported whatever the process was doing`() {
        // A native crash in a background service is still a native crash.
        listOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ).forEach { reason ->
            assertTrue("$reason should report", worthReporting(reason, cached))
        }
    }

    @Test
    fun `ordinary exits are never reported`() {
        listOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_OTHER,
        ).forEach { reason ->
            assertFalse("$reason should stay quiet", worthReporting(reason, foreground))
        }
    }

    @Test
    fun `a report already shown is not shown again`() {
        val crash = ApplicationExitInfo.REASON_CRASH_NATIVE
        assertTrue(worthReporting(crash, foreground, at = 500L, seen = 400L))
        assertFalse(worthReporting(crash, foreground, at = 500L, seen = 500L))
        assertFalse(worthReporting(crash, foreground, at = 500L, seen = 900L))
    }

    @Test
    fun `a newer crash after one already seen still reports`() {
        // The history holds several records; having acknowledged one must not
        // silence the next.
        assertTrue(
            worthReporting(ApplicationExitInfo.REASON_CRASH, foreground, at = 900L, seen = 500L),
        )
    }
}
