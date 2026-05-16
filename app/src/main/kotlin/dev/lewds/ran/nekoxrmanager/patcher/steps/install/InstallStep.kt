package dev.lewds.ran.nekoxrmanager.patcher.steps.install

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.installers.InstallResult
import dev.lewds.ran.nekoxrmanager.manager.InstallerManager
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step

/**
 * Hands the patched APK to the chosen installer (Shizuku silent install, or system
 * dialog via FileProvider). PENDING_USER_CONFIRM is treated as success — the dialog
 * was launched, the user takes it from there.
 */
class InstallStep : Step() {
    override val group = StepGroup.Install
    override val localizedName = R.string.step_install

    override suspend fun execute(container: NekoPatchRunner) {
        val apk = container.outputApk ?: error("no patched APK")
        val mgr = InstallerManager(container.appContext, ServiceLocator.prefs())
        val installer = mgr.pick()
        container.installedSilently = mgr.shizukuActive()

        val log = ServiceLocator.log()
        val result = installer.install(apk)
        when (result.kind) {
            InstallResult.Kind.SUCCESS -> log.info("install", "installed silently via Shizuku")
            InstallResult.Kind.PENDING_USER_CONFIRM -> log.info("install", "system installer launched: ${result.message}")
            InstallResult.Kind.FAILED -> error("install failed: ${result.message}")
        }
    }
}
