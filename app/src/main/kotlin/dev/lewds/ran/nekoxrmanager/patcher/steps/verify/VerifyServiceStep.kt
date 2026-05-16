package dev.lewds.ran.nekoxrmanager.patcher.steps.verify

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ShizukuShell
import kotlinx.coroutines.delay

/**
 * Soft post-install confirmation. With Shizuku we can immediately {@code pm list packages}
 * and confirm; without it we wait briefly for the package manager to settle and check the
 * in-process PackageManager.
 *
 * This step never fails the pipeline — install may still be pending user confirmation.
 */
class VerifyServiceStep : Step() {
    override val group = StepGroup.Verify
    override val localizedName = R.string.step_verify_service

    override suspend fun execute(container: NekoPatchRunner) {
        val log = ServiceLocator.log()
        val pkg = container.targetPackage
        if (!container.installedSilently) {
            log.info("verify", "non-silent install; cannot confirm in-process")
            skip(); return
        }
        delay(500)
        if (ShizukuShell.isAvailable()) {
            val r = ShizukuShell.sh("pm list packages $pkg", 4_000)
            if (r.ok() && r.stdout.contains(pkg)) {
                log.info("verify", "confirmed: $pkg installed")
                return
            }
        }
        // Fallback: in-process query.
        try {
            container.appContext.packageManager.getPackageInfo(pkg, 0)
            log.info("verify", "confirmed via in-process PackageManager")
        } catch (t: Throwable) {
            log.warn("verify", "could not confirm install: ${t.message}")
        }
    }
}
