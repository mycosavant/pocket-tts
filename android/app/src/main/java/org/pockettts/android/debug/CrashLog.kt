package org.pockettts.android.debug

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records the last uncaught exception where a person can actually get at it.
 *
 * Sideloading onto a phone means no logcat, and an app that dies silently gives
 * back exactly one bit of information: "it crashed". That is not enough to fix
 * anything. This writes the stack trace to app storage on the way down, so the
 * next launch can offer to share it.
 *
 * It deliberately does not swallow the crash. The previous handler still runs,
 * the process still dies, and the system dialog still appears - the only change
 * is that the evidence survives.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /** Guards against a crash *inside* the handler turning into a loop. */
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(appContext.filesDir, FILE_NAME).writeText(
                    formatReport(
                        thread = thread.name,
                        error = error,
                        metadata = deviceMetadata(appContext),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
            // Chain, so the platform still does whatever it would have done.
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? =
        File(context.applicationContext.filesDir, FILE_NAME)
            .takeIf { it.isFile && it.length() > 0 }
            ?.runCatching { readText() }
            ?.getOrNull()

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }

    /**
     * Builds the report body.
     *
     * Kept pure and separate from the handler so it can be tested: a crash
     * reporter that itself throws while formatting is worse than none at all.
     */
    fun formatReport(
        thread: String,
        error: Throwable,
        metadata: Map<String, String>,
        timestamp: Long,
    ): String = buildString {
        appendLine("Pocket TTS crash report")
        appendLine("when: ${java.util.Date(timestamp)}")
        appendLine("thread: $thread")
        for ((key, value) in metadata) appendLine("$key: $value")
        appendLine()
        append(stackTraceOf(error))
    }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    fun deviceMetadata(context: Context): Map<String, String> {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")

        return linkedMapOf(
            "app" to version,
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "abis" to Build.SUPPORTED_ABIS.joinToString(", "),
        )
    }
}
