package dev.lewds.ran.nekoxrmanager.patcher.steps.install

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ZipUtil

/**
 * Rebuilds the APK from scratch so apksig is handed a structurally clean ZIP —
 * no leftover signing block, no orphans from in-place entry replacement.
 */
class RepackageStep : Step() {
    override val group = StepGroup.Sign
    override val localizedName = R.string.step_repackage

    override suspend fun execute(container: NekoPatchRunner) {
        val apk = container.outputApk ?: error("no working APK")
        val sizeBefore = apk.length()
        ZipUtil.rebuildClean(apk)
        ServiceLocator.log().info(
            "repackage",
            "rebuilt ${apk.name} (${sizeBefore} → ${apk.length()} B)"
        )
    }
}
