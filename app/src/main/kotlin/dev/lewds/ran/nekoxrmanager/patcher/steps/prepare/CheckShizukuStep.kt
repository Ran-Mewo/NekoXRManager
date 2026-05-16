package dev.lewds.ran.nekoxrmanager.patcher.steps.prepare

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ShizukuShell

/**
 * Soft-warns if Shizuku is unavailable. This step never fails — Shizuku is preferred
 * for silent install + richer device harvest, but the patcher can still produce a
 * patched APK without it (PMInstaller fallback + Camera2-only harvest).
 */
class CheckShizukuStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.step_check_shizuku

    var available: Boolean = false
        private set

    override suspend fun execute(container: NekoPatchRunner) {
        available = ShizukuShell.isAvailable()
        if (available) {
            ServiceLocator.log().info("shizuku", "available — silent install + shell harvest enabled")
        } else {
            ServiceLocator.log().warn("shizuku",
                "not available; falling back to PMInstaller + Camera2-only harvest")
        }
    }
}
