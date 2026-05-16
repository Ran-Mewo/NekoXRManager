package dev.lewds.ran.nekoxrmanager.patcher.steps.base

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.measureTimedValue

/**
 * One unit of patcher work. Single-use; once `state.isFinished`, do not re-run.
 * Concrete impls keep their bodies short and delegate to the Java utilities under
 * `dev.lewds.ran.nekoxrmanager.patcher.util.*`.
 */
@Stable
abstract class Step {
    abstract val group: StepGroup

    @get:StringRes
    abstract val localizedName: Int

    /** The actual work. Throw to fail the step. */
    protected abstract suspend fun execute(container: NekoPatchRunner)

    var state by mutableStateOf(StepState.Pending)
        protected set

    /** -1 means progress is indeterminate. */
    var progress by mutableFloatStateOf(-1f)
        protected set

    private var startTime: Long? = null
    private var totalTimeMs: Long? = null

    /** Wall-clock duration in ms; 0 if not started, frozen value if finished, live value if running. */
    fun getDuration(): Long {
        val s = startTime ?: return 0
        totalTimeMs?.let { return it }
        return System.currentTimeMillis() - s
    }

    /** Mark this step as skipped instead of run. Only valid before/during execute(). */
    protected fun skip() {
        state = StepState.Skipped
    }

    /** Update progress 0..1, or pass -1 to mark indeterminate. */
    protected fun progress(value: Float) {
        progress = value
    }

    /** Runs execute() with timing + state transitions. Returns the throwable if the step failed. */
    suspend fun executeCatching(container: NekoPatchRunner): Throwable? {
        check(state == StepState.Pending) { "step already executed: ${javaClass.simpleName}" }
        state = StepState.Running
        startTime = System.currentTimeMillis()

        val (error, took) = measureTimedValue {
            try {
                withContext(Dispatchers.Default) { execute(container) }
                if (state != StepState.Skipped) state = StepState.Success
                null
            } catch (t: Throwable) {
                state = StepState.Error
                t
            }
        }
        totalTimeMs = took.inWholeMilliseconds
        return error
    }
}
