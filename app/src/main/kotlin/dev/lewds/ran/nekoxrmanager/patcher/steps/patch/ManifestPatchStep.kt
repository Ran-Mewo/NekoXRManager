package dev.lewds.ran.nekoxrmanager.patcher.steps.patch

import com.github.diamondminer88.zip.ZipCompression
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ManifestPatcher
import dev.lewds.ran.nekoxrmanager.patcher.util.ZipUtil

private const val MANIFEST_ENTRY = "AndroidManifest.xml"

/**
 * Edits the binary AndroidManifest.xml so {@code extractNativeLibs=true} (otherwise our
 * patched libarcore_c.so won't ever land on disk for the linker) and adds
 * {@code <uses-feature android:name="android.hardware.camera.ar" required="false"/>}.
 */
class ManifestPatchStep : Step() {
    override val group = StepGroup.Patch
    override val localizedName = R.string.step_patch_manifest

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        val apk = container.outputApk ?: error("no working APK")
        val original = ZipUtil.readEntry(apk, MANIFEST_ENTRY)
            ?: error("APK has no $MANIFEST_ENTRY")

        val opts = ManifestPatcher.Options().apply {
            setDebuggable = ServiceLocator.prefs().isDebuggable
            ensureExtractNativeLibsTrue = true
            ensureCameraArFeature = true
        }
        val patched = ManifestPatcher.patch(original, opts)
        ZipUtil.replaceEntry(apk, MANIFEST_ENTRY, patched, ZipCompression.NONE)
        ServiceLocator.log().info("manifest",
            "rewrote AndroidManifest.xml (${original.size} → ${patched.size} B)")
    }
}
