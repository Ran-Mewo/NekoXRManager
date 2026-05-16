package dev.lewds.ran.nekoxrmanager.patcher.steps.prepare

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ZipUtil
import java.io.File
import java.nio.file.Files

/**
 * Copies the source APK into a fresh job directory so we never edit the cached download.
 * The patched copy lives at {@code <jobDir>/arcore-patched.apk} and is the file every
 * subsequent patch step touches.
 */
class ExtractApkStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.step_extract_apk

    override suspend fun execute(container: NekoPatchRunner) {
        val src = container.sourceApk ?: error("no source APK")
        val jobDir = ServiceLocator.paths().freshJobDir()
        val dst = File(jobDir, "arcore-patched.apk")
        ZipUtil.copyApk(src, dst)
        container.extractedDir = jobDir
        container.outputApk = dst

        val libs = ZipUtil.listEntries(dst, "lib/")
        ServiceLocator.log().info("extract",
            "copied ${dst.length()} B → ${dst.name}; ${libs.size} lib/* entries")
    }
}
