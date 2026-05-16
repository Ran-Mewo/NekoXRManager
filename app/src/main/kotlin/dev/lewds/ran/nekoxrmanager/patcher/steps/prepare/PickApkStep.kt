package dev.lewds.ran.nekoxrmanager.patcher.steps.prepare

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * SAF fallback: if [DownloadArCoreStep] succeeded we skip; otherwise the runner expects
 * [NekoPatchRunner.userApkOverride] to be set (the UI surfaces a SAF picker before kicking
 * off the pipeline). The picked Uri is copied to the cache dir so the rest of the pipeline
 * deals with a stable on-disk File.
 */
class PickApkStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.step_pick_apk

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.sourceApk != null) { skip(); return }
        val uri = container.userApkOverride
            ?: throw IllegalStateException("no ARCore APK: download skipped and no user-picked APK provided")

        val resolver = container.appContext.contentResolver
        val dst = File(ServiceLocator.paths().cacheDir, "user_picked.apk")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open user-picked APK uri: $uri" }
            Files.copy(input, dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        container.sourceApk = dst
        ServiceLocator.log().info("pick", "user APK copied → ${dst.name} (${dst.length()} B)")
    }
}
