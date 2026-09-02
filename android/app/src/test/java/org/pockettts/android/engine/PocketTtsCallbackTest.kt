package org.pockettts.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape of the audio callback handed across the JNI boundary.
 *
 * sherpa-onnx resolves this callback from native code by name and exact
 * signature - `invoke([F)Ljava/lang/Integer;`. Nothing on the Kotlin side
 * references that signature, so nothing on the Kotlin side fails when it stops
 * existing: it compiled, packaged, passed every other test, and aborted the
 * process from native code on the first synthesised chunk.
 *
 * That is exactly what a Kotlin lambda did here. Kotlin 2.0 emits lambdas via
 * `invokedynamic`, and D8 desugars them into `$$ExternalSyntheticLambda`
 * classes that carry only the erased `invoke(Object)Object`. `GetMethodID`
 * found nothing, the JNI call continued with a pending exception, and the
 * runtime killed the process - reported as `REASON_CRASH_NATIVE` with no Java
 * stack trace anywhere.
 *
 * These tests fail if the callback ever goes back to being a lambda.
 */
class PocketTtsCallbackTest {

    private val callback = PocketTts.audioCallback { true }

    @Test
    fun `exposes the specialised invoke signature the JNI looks up`() {
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(
            "sherpa-onnx looks up invoke([F)Ljava/lang/Integer;",
            java.lang.Integer::class.java,
            method.returnType,
        )
    }

    @Test
    fun `is not a desugared invokedynamic lambda`() {
        val name = callback.javaClass.name
        assertFalse(
            "callback compiled to a desugared lambda ($name); the JNI cannot resolve those",
            name.contains("ExternalSyntheticLambda"),
        )
    }

    @Test
    fun `returns 1 to continue and 0 to stop, which is what sherpa-onnx reads`() {
        assertEquals(1, PocketTts.audioCallback { true }.invoke(FloatArray(4)))
        assertEquals(0, PocketTts.audioCallback { false }.invoke(FloatArray(4)))
    }

    @Test
    fun `passes the samples through untouched`() {
        var seen: FloatArray? = null
        val samples = floatArrayOf(0.1f, -0.2f, 0.3f)
        PocketTts.audioCallback { seen = it; true }.invoke(samples)
        assertTrue(samples.contentEquals(seen))
    }

    @Test
    fun `the erased bridge is present too, since generic callers use it`() {
        val bridge = callback.javaClass.getMethod("invoke", Any::class.java)
        assertEquals(1, bridge.invoke(callback, FloatArray(0)))
    }
}
