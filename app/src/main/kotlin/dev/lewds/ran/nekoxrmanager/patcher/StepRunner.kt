package dev.lewds.ran.nekoxrmanager.patcher

import android.util.Log
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import kotlinx.coroutines.delay

/**
 * Sequential async pipeline runner. Each [Step] runs in order, on Dispatchers.Default.
 * On any failure, the rest of the pipeline is skipped and the throwable is returned.
 *
 * Subclasses define [steps] and may attach shared state that step impls read/write —
 * [NekoPatchRunner] is the concrete instance for ARCore patching.
 */
abstract class StepRunner {

    /** Pipeline definition. Order matters; do not mutate after [executeAll] starts. */
    abstract val steps: List<Step>

    /** Look up an earlier step by type — used when a later step needs an upstream result. */
    inline fun <reified T : Step> getStep(): T =
        steps.asSequence().filterIsInstance<T>().firstOrNull()
            ?: error("no step ${T::class.simpleName} in pipeline")

    inline fun <reified T : Step> getStepOrNull(): T? =
        steps.asSequence().filterIsInstance<T>().firstOrNull()

    /** Lift a free-form log line into the install log; also Logcat. */
    fun log(line: String) {
        ServiceLocator.log().info("runner", line)
        Log.i("NekoXR", line)
    }

    /**
     * Runs every step. Returns null on success, or the failing step's throwable.
     * The minimum step delay is intentional — quick step flashes look like a bug to users.
     */
    suspend fun executeAll(): Throwable? {
        log("starting pipeline (${steps.size} steps)")
        for (step in steps) {
            val name = step.javaClass.simpleName
            log("> $name")
            val err = step.executeCatching(this as NekoPatchRunner)
            if (err != null) {
                log("! $name failed in ${step.getDuration()}ms: ${err.message}")
                return err
            }
            val took = step.getDuration()
            if (took < MINIMUM_STEP_DELAY) delay(MINIMUM_STEP_DELAY - took)
            log("< $name in ${took}ms (${step.state})")
        }
        log("pipeline finished in ${steps.sumOf { it.getDuration() }}ms")
        return null
    }

    private companion object {
        /** Smooth out micro-fast steps so the patching screen doesn't strobe. */
        const val MINIMUM_STEP_DELAY = 350L
    }
}
