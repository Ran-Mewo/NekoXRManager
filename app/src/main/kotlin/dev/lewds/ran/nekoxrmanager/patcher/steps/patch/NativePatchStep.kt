package dev.lewds.ran.nekoxrmanager.patcher.steps.patch

import com.github.diamondminer88.zip.ZipCompression
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.NativePatcher
import dev.lewds.ran.nekoxrmanager.patcher.util.ZipUtil
import java.io.File
import java.nio.file.Files

/**
 * Extracts {@code lib/<abi>/libarcore_c.so} from the APK, runs [NativePatcher.apply],
 * and writes the patched bytes back as a STORED entry (extractNativeLibs makes the
 * loader use the on-disk copy regardless, but uncompressed keeps the install fast).
 *
 * Single-match-or-fail per pattern is enforced by NativePatcher; this step propagates
 * that as a hard pipeline failure.
 */
class NativePatchStep : Step() {
    override val group = StepGroup.Patch
    override val localizedName = R.string.step_native_patch

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        val apk = container.outputApk ?: error("no working APK")
        val set = container.patchSet ?: error("no patch set")
        val workDir = container.extractedDir ?: error("no work dir")
        val log = ServiceLocator.log()

        val so = File(workDir, set.so.replace('/', java.io.File.separatorChar))
        ZipUtil.extractEntry(apk, set.so, so)
        val results = NativePatcher.apply(so, set)
        log.info("native", "patched ${set.so} (${results.size} site${if (results.size == 1) "" else "s"})")

        ZipUtil.replaceEntry(apk, set.so, Files.readAllBytes(so.toPath()), ZipCompression.NONE)
    }
}
