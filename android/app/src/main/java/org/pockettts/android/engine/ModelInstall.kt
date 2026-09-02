package org.pockettts.android.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place the model is installed from.
 *
 * There were two callers - the download button, and any read that reached
 * `PocketTts.get` before the model existed - and neither knew about the other.
 * They shared a partial-download file and a staging directory, so tapping
 * Download and then selecting text in another app had them writing the same
 * `.part` and deleting each other's unpacked files.
 *
 * The download also belonged to the activity that started it. A configuration
 * change cancelled the coroutine, but the transfer itself has no suspension
 * point, so it carried on headless while the recreated screen showed "not
 * downloaded" and invited a second tap - and the partial file was deleted on
 * the way out, so the second tap started from zero.
 *
 * So: one shared attempt, owned by the process. Everybody who asks joins the
 * one in flight rather than starting another, and a screen that goes away
 * leaves it running.
 */
object ModelInstall {

    sealed interface State {
        data object Idle : State

        /** [fraction] is 0..1, or -1 when the size is not known. */
        data class Working(val fraction: Float) : State

        data object Installed : State

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var attempt: Deferred<ModelManager.ModelFiles>? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = attempt?.isActive == true

    /**
     * Installs the model if it is not already there, joining an attempt that is
     * already under way.
     *
     * Cancelling the caller does not cancel the download: it is shared, and one
     * screen navigating away is not a decision to abandon 98 MB that other
     * callers may still be waiting on.
     */
    suspend fun ensure(context: Context): ModelManager.ModelFiles {
        val app = context.applicationContext
        ModelManager(app).resolveModelOrNull()?.let {
            _state.value = State.Installed
            return it
        }

        val shared = lock.withLock {
            attempt?.takeIf { it.isActive } ?: scope.async { install(app) }.also { attempt = it }
        }
        return shared.await()
    }

    private suspend fun install(context: Context): ModelManager.ModelFiles {
        _state.value = State.Working(0f)
        return try {
            installer.install(context) { fraction -> _state.value = State.Working(fraction) }
                .also { _state.value = State.Installed }
        } catch (error: Throwable) {
            _state.value = State.Failed(error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    /** How the model actually gets here. Swapped in tests, which have no network. */
    internal fun interface Installer {
        suspend fun install(
            context: Context,
            progress: ModelManager.ProgressListener?,
        ): ModelManager.ModelFiles
    }

    internal var installer = Installer { context, progress ->
        ModelManager(context).ensureModel(progress)
    }

    internal fun resetForTesting() {
        attempt = null
        _state.value = State.Idle
        installer = Installer { context, progress -> ModelManager(context).ensureModel(progress) }
    }
}
