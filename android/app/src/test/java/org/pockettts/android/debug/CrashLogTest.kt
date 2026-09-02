package org.pockettts.android.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {

    private fun report(error: Throwable) = CrashLog.formatReport(
        thread = "main",
        error = error,
        metadata = linkedMapOf("app" to "0.1.0", "android" to "15 (API 35)"),
        timestamp = 0L,
    )

    @Test
    fun `report carries the exception type and message`() {
        val text = report(IllegalStateException("action bar already supplied"))
        assertTrue(text, text.contains("IllegalStateException"))
        assertTrue(text, text.contains("action bar already supplied"))
    }

    @Test
    fun `report carries the thread and metadata`() {
        val text = report(RuntimeException("boom"))
        assertTrue(text, text.contains("thread: main"))
        assertTrue(text, text.contains("app: 0.1.0"))
        assertTrue(text, text.contains("android: 15 (API 35)"))
    }

    @Test
    fun `report includes the stack frames`() {
        val text = report(RuntimeException("boom"))
        assertTrue(text, text.contains("at org.pockettts.android.debug.CrashLogTest"))
    }

    @Test
    fun `causes are preserved, since the useful frame is usually in the cause`() {
        val cause = IllegalArgumentException("the real problem")
        val text = report(RuntimeException("wrapper", cause))
        assertTrue(text, text.contains("Caused by"))
        assertTrue(text, text.contains("the real problem"))
    }

    @Test
    fun `an exception with no message still formats`() {
        val text = report(NullPointerException())
        assertTrue(text, text.contains("NullPointerException"))
        assertFalse(text.isBlank())
    }

    @Test
    fun `a self-referencing cause does not hang the formatter`() {
        // printStackTrace handles cycles, but this is the kind of input that
        // turns a crash reporter into a second crash, so it is worth pinning.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val text = report(b)
        assertTrue(text, text.contains("RuntimeException"))
    }
}
