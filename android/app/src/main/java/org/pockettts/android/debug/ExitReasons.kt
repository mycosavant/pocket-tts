package org.pockettts.android.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Date

/**
 * Reports why the previous process died, including deaths a crash handler cannot see.
 *
 * [CrashLog] only catches exceptions that unwind through the JVM. A native
 * segfault, an ANR, and a low-memory kill all bypass it entirely and leave the
 * app looking like it "just closed" - which is a symptom shared by about six
 * unrelated causes, so it identifies none of them.
 *
 * The platform already records the answer. `getHistoricalProcessExitReasons`
 * returns a reason code per death, and from Android 12 a native crash carries
 * its tombstone. Reading it turns a guess into a fact.
 */
object ExitReasons {

    /** A death worth telling the user about, already rendered for display. */
    data class Report(val summary: String, val detail: String)

    /**
     * Deaths that mean something went wrong. A process that exited on request,
     * or was trimmed while idle in the background, is ordinary housekeeping and
     * is not worth surfacing.
     */
    private val INTERESTING = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    /** The most recent noteworthy death, or null if the last exit was unremarkable. */
    fun lastInterestingExit(context: Context): Report? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { readLastExit(context) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLastExit(context: Context): Report? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val history = activityManager.getHistoricalProcessExitReasons(
            context.packageName,
            /* pid = */ 0,
            MAX_RECORDS,
        )
        val exit = history.firstOrNull { it.reason in INTERESTING } ?: return null

        return Report(
            summary = summarise(exit),
            detail = describe(exit, CrashLog.deviceMetadata(context)),
        )
    }

    /** One line naming what happened, which is usually the whole answer. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun summarise(exit: ApplicationExitInfo): String = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "Native crash - the failure was inside ONNX Runtime or sherpa-onnx, not app code."

        ApplicationExitInfo.REASON_CRASH ->
            "Java crash - an uncaught exception."

        ApplicationExitInfo.REASON_ANR ->
            "Not responding - the main thread was blocked long enough for the system to kill it."

        ApplicationExitInfo.REASON_LOW_MEMORY ->
            "Killed for memory - the system needed the RAM back."

        ApplicationExitInfo.REASON_SIGNALED ->
            "Killed by signal ${exit.status}."

        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "Killed for excessive resource use."

        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
            "The process failed to start."

        ApplicationExitInfo.REASON_PERMISSION_CHANGE ->
            "Restarted because a permission changed."

        else -> "Process exited: ${reasonName(exit.reason)}"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun describe(exit: ApplicationExitInfo, metadata: Map<String, String>): String = buildString {
        appendLine("Pocket TTS exit report")
        appendLine("when: ${Date(exit.timestamp)}")
        appendLine("reason: ${reasonName(exit.reason)} (${exit.reason})")
        appendLine("status: ${exit.status}")
        appendLine("importance: ${exit.importance}")
        appendLine("rss: ${exit.rss / 1024} MB")
        appendLine("pss: ${exit.pss / 1024} MB")
        exit.description?.let { appendLine("description: $it") }
        for ((key, value) in metadata) appendLine("$key: $value")

        // A native crash carries its tombstone from Android 12 onward, and that
        // is the only place the offending frame is recorded.
        tombstoneOf(exit)?.let {
            appendLine()
            appendLine("--- tombstone ---")
            append(it)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun tombstoneOf(exit: ApplicationExitInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (exit.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return null
        return runCatching {
            exit.traceInputStream?.bufferedReader()?.use { reader ->
                // Tombstones are protobuf and can be large; the head carries the
                // signal, the fault address and the crashing frames.
                buildString {
                    var lines = 0
                    while (lines < TOMBSTONE_LINES) {
                        val line = reader.readLine() ?: break
                        appendLine(line)
                        lines++
                    }
                }
            }
        }.getOrNull()
    }

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN($reason)"
    }

    private const val MAX_RECORDS = 5
    private const val TOMBSTONE_LINES = 120
}
